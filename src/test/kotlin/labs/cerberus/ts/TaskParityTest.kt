package labs.cerberus.ts

import labs.cerberus.ts.service.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class TaskParityTest {
    private val timeout = Duration.ofSeconds(3)
    private fun latch(latch: CountDownLatch) = assertTrue(latch.await(3, TimeUnit.SECONDS))
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue(condition())
    }
    private fun task(name: String = "test", delay: Duration = Duration.ZERO, action: Runnable = Runnable {}): Task =
        Task.builder().name(name).delay(delay).runnable(action).build()

    @Test fun `builder captures time units actions execution and repeat conditions independently of reset`() {
        val count = AtomicInteger()
        val builder = Task.builder().name("first").interval(50, TimeUnit.MILLISECONDS).initialDelay(Duration.ZERO)
            .execution(TaskExecution.SERIAL).runnable(Runnable { count.incrementAndGet() }).shouldReschedule { false }
        val first = builder.build()
        builder.reset().name("second").delay(2, TimeUnit.DAYS).runnable(Runnable { count.addAndGet(10) })
        val second = builder.build()
        assertEquals(Duration.ofMillis(50), first.interval); assertEquals(Duration.ZERO, first.delay)
        assertEquals(TaskExecution.SERIAL, first.execution); assertEquals(TaskType.REPEAT, first.type)
        assertFalse(first.shouldReschedule()); first.runnable.run(); assertEquals(1, count.get())
        assertEquals(Duration.ofDays(2), second.delay); assertNull(second.interval); assertEquals(TaskExecution.ASYNC, second.execution)
        assertFails { Task.builder().build() }; assertFails { Task.builder().name(" ") }
        assertFails { builder.interval(Duration.ZERO) }; assertFails { builder.delay(Duration.ofNanos(-1)) }
        assertFailsWith<ArithmeticException> { builder.delay(Long.MAX_VALUE, TimeUnit.DAYS) }
        TaskService().use { service ->
            val handle = service.scheduleTask(first)
            assertTrue(handle.awaitTermination(timeout)); assertEquals(TaskState.COMPLETED, handle.state)
            assertEquals(1, handle.snapshot.runs); assertEquals(2, count.get())
        }
    }

    @Test fun `registration uses object identity duplicate scheduling is idempotent and old handles cannot cancel replacements`() {
        class EqualTask(private val base: Task) : Task by base {
            override fun equals(other: Any?) = other is EqualTask
            override fun hashCode() = 1
        }
        TaskService().use { service ->
            val a = EqualTask(task("same name", Duration.ofDays(1)))
            val b = EqualTask(task("same name", Duration.ofDays(1)))
            val first = service.scheduleTask(a); val other = service.scheduleTask(b)
            assertSame(first, service.scheduleTask(a)); assertEquals(2, service.snapshots().size)
            assertEquals(TaskState.SCHEDULED, first.state); assertNotNull(first.snapshot.nextRunAt)
            assertSame(first, service.rescheduleTask(a))
            assertSame(first, service.runTask(a)); assertTrue(first.awaitTermination(timeout))
            assertFalse(service.hasTask(a)); assertTrue(service.hasTask(b))
            val replacement = service.rescheduleTask(a)
            assertNotEquals(first.id, replacement.id); assertFalse(first.cancel()); assertTrue(service.hasTask(a))
            assertTrue(service.removeTask(a)); assertTrue(replacement.isCancelled)
            assertFalse(service.removeTask(a)); assertTrue(other.cancel()); assertTrue(service.snapshots().isEmpty())
        }
    }

    @Test fun `serial tasks share a worker async tasks progress independently and queued cancellation skips execution`() {
        TaskService(workers = 2).use { service ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val asyncRan = CountDownLatch(1)
            val skipped = AtomicInteger(); val serialThread = AtomicLong()
            val first = service.scheduleTask(Task.builder().name("serial blocker").execution(TaskExecution.SERIAL).runnable(Runnable {
                serialThread.set(Thread.currentThread().threadId()); entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
            }).build())
            try {
                latch(entered)
                val queued = service.scheduleTask(Task.builder().name("serial queued").execution(TaskExecution.SERIAL)
                    .runnable(Runnable { skipped.incrementAndGet() }).build())
                waitUntil { queued.state == TaskState.QUEUED }
                val async = service.scheduleTask(task("async", action = Runnable { asyncRan.countDown() }))
                latch(asyncRan); assertTrue(async.awaitTermination(timeout)); assertEquals(0, skipped.get())
                assertTrue(queued.cancel()); assertTrue(queued.awaitTermination(timeout)); assertEquals(TaskState.CANCELLED, queued.state)
                release.countDown(); assertTrue(first.awaitTermination(timeout))
                val last = service.scheduleTask(Task.builder().name("serial next").execution(TaskExecution.SERIAL).runnable(Runnable {
                    assertEquals(serialThread.get(), Thread.currentThread().threadId())
                }).build())
                assertTrue(last.awaitTermination(timeout)); assertEquals(0, skipped.get()); assertEquals(TaskState.COMPLETED, last.state)
            } finally { release.countDown() }
        }
    }

    @Test fun `rescheduling a running repeat coalesces requests without overlap and failures do not stop later runs`() {
        val count = AtomicInteger(); val concurrent = AtomicInteger(); val maximum = AtomicInteger()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val errors = CopyOnWriteArrayList<Throwable>()
        TaskService(onError = { _, error -> errors += error }).use { service ->
            val task = Task.builder().name("repeat").interval(Duration.ofMillis(5)).initialDelay(Duration.ZERO).runnable(Runnable {
                maximum.accumulateAndGet(concurrent.incrementAndGet(), ::maxOf)
                try {
                    when (count.incrementAndGet()) {
                        1 -> { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
                        2 -> error("recoverable invocation failure")
                    }
                } finally { concurrent.decrementAndGet() }
            }).shouldReschedule { count.get() < 3 }.build()
            val handle = service.scheduleTask(task)
            try {
                latch(entered)
                repeat(25) { assertTrue(handle.runNow()) }
                assertEquals(TaskState.RUNNING, handle.state); assertEquals(1, count.get())
                release.countDown(); assertTrue(handle.awaitTermination(timeout))
                assertEquals(1, maximum.get()); assertEquals(3, count.get()); assertEquals(3, handle.snapshot.runs)
                assertEquals(1, handle.snapshot.failures); assertEquals(1, errors.size)
                assertEquals("recoverable invocation failure", handle.snapshot.lastFailure?.message)
                assertEquals(TaskState.COMPLETED, handle.state)
            } finally { release.countDown() }
        }
    }

    @Test fun `cancelled running tasks cannot resurrect even when their action ignores interruption`() {
        val entered = CountDownLatch(1); val interrupted = CountDownLatch(1); val release = CountDownLatch(1)
        val enteredAgain = CountDownLatch(1); val releaseAgain = CountDownLatch(1); val calls = AtomicInteger()
        TaskService().use { service ->
            val task = Task.builder().name("stubborn").interval(Duration.ofMillis(1)).initialDelay(Duration.ZERO).runnable(Runnable {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown()
                    while (release.count > 0) try { release.await() } catch (_: InterruptedException) { interrupted.countDown() }
                } else { enteredAgain.countDown(); check(releaseAgain.await(3, TimeUnit.SECONDS)) }
            }).shouldReschedule { false }.build()
            val old = service.scheduleTask(task)
            try {
                latch(entered); assertTrue(old.cancel()); latch(interrupted)
                assertTrue(old.isCancelled); assertTrue(old.isRunning); assertFalse(service.hasTask(task))
                assertFalse(old.runNow()); assertFalse(old.reschedule()); assertFails { service.scheduleTask(task) }
                assertFalse(old.awaitTermination(Duration.ZERO))
                release.countDown(); assertTrue(old.awaitTermination(timeout)); assertFalse(old.isRunning)
                assertEquals(1, calls.get())
                val fresh = service.scheduleTask(task); latch(enteredAgain)
                assertFalse(old.cancel()); assertTrue(fresh.isRunning); assertTrue(service.hasTask(task))
                releaseAgain.countDown(); assertTrue(fresh.awaitTermination(timeout)); assertEquals(2, calls.get())
            } finally { release.countDown(); releaseAgain.countDown() }
        }
    }

    @Test fun `predicate errors fatal errors limits and shutdown have observable terminal states`() {
        val reports = AtomicInteger()
        val service = TaskService(maxTasks = 1, onError = { _, _ -> reports.incrementAndGet(); error("reporting failed") })
        val delayed = service.scheduleTask(task(delay = Duration.ofDays(1)))
        assertFails { service.scheduleTask(task()) }; assertTrue(delayed.cancel(false))
        val predicate = service.scheduleTask(Task.builder().name("predicate").interval(Duration.ofNanos(1)).initialDelay(Duration.ZERO)
            .runnable(Runnable {}).shouldReschedule { error("predicate failed") }.build())
        assertTrue(predicate.awaitTermination(timeout)); assertEquals(TaskState.FAILED, predicate.state)
        assertEquals(1, predicate.snapshot.failures); assertEquals(1, reports.get())
        val fatal = service.scheduleTask(Task.builder().name("fatal").interval(Duration.ofNanos(1)).initialDelay(Duration.ZERO)
            .runnable(Runnable { throw AssertionError("fatal invocation") }).build())
        assertTrue(fatal.awaitTermination(timeout)); assertEquals(TaskState.FAILED, fatal.state); assertEquals(2, reports.get())
        val healthy = service.scheduleTask(task()); assertTrue(healthy.awaitTermination(timeout)); assertEquals(TaskState.COMPLETED, healthy.state)
        val pending = service.scheduleTask(task(delay = Duration.ofDays(1)))
        service.close(); service.close()
        assertTrue(pending.awaitTermination(timeout)); assertEquals(TaskState.CANCELLED, pending.state)
        assertFalse(pending.runNow()); assertTrue(service.snapshots().isEmpty()); assertFails { service.scheduleTask(task()) }
    }
}
