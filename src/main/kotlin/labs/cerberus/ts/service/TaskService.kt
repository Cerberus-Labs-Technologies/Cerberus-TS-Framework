package labs.cerberus.ts.service

import java.time.Duration
import java.time.Instant
import java.util.IdentityHashMap
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

enum class TaskType { DELAY, REPEAT }
/** SERIAL shares one bot-owned worker; neither mode runs on a TeamSpeak server thread. */
enum class TaskExecution { ASYNC, SERIAL }
enum class TaskState { SCHEDULED, QUEUED, RUNNING, COMPLETED, CANCELLED, FAILED }

interface Task {
    val name: String
    val delay: Duration
    val interval: Duration?
    val runnable: Runnable
    val execution: TaskExecution get() = TaskExecution.ASYNC
    val type: TaskType get() = if (interval == null) TaskType.DELAY else TaskType.REPEAT
    fun shouldReschedule(): Boolean = true
    companion object { @JvmStatic fun builder() = TaskBuilder() }
}

/** Built values never reference this builder's mutable fields. */
class TaskBuilder {
    private var name: String? = null
    private var delay = Duration.ZERO
    private var interval: Duration? = null
    private var runnable: Runnable? = null
    private var execution = TaskExecution.ASYNC
    private var repeat: () -> Boolean = { true }
    fun name(value: String) = apply { require(value.isNotBlank()); name = value }
    fun delay(value: Long, unit: TimeUnit) = delay(Duration.of(value, unit.toChronoUnit()))
    fun delay(value: Duration) = apply { nanos(value, true); delay = value; interval = null }
    /** Like Jeak, the first run waits for one interval; initialDelay() may override it. */
    fun interval(value: Long, unit: TimeUnit) = interval(Duration.of(value, unit.toChronoUnit()))
    fun interval(value: Duration) = apply { nanos(value, false); interval = value; delay = value }
    fun initialDelay(value: Duration) = apply { nanos(value, true); delay = value }
    fun runnable(value: Runnable) = apply { runnable = value }
    fun execution(value: TaskExecution) = apply { execution = value }
    fun shouldReschedule(value: () -> Boolean) = apply { repeat = value }
    fun reset() = apply {
        name = null; delay = Duration.ZERO; interval = null; runnable = null; execution = TaskExecution.ASYNC; repeat = { true }
    }
    fun build(): Task {
        val taskName = requireNotNull(name) { "Task needs a name" }
        val action = requireNotNull(runnable) { "Task needs a runnable" }
        val initial = delay; val period = interval; val mode = execution; val condition = repeat
        return object : Task {
            override val name = taskName
            override val delay = initial
            override val interval = period
            override val runnable = action
            override val execution = mode
            override fun shouldReschedule() = condition()
        }
    }
}

data class TaskSnapshot(val id: Long, val name: String, val execution: TaskExecution, val type: TaskType,
    val state: TaskState, val runs: Long, val failures: Long, val lastFailure: Throwable?, val nextRunAt: Instant?, val running: Boolean)

class TaskHandle internal constructor(private val service: TaskService, internal val control: TaskService.Control) : AutoCloseable {
    val id: Long get() = control.id
    val task: Task get() = control.task
    val snapshot: TaskSnapshot get() = service.snapshot(control)
    val state: TaskState get() = snapshot.state
    val isCancelled: Boolean get() = state == TaskState.CANCELLED
    val isRunning: Boolean get() = snapshot.running
    fun cancel(interrupt: Boolean = true): Boolean = service.cancel(control, interrupt)
    fun reschedule(delay: Duration = control.definition.delay): Boolean = service.replan(control, delay)
    fun runNow(): Boolean = reschedule(Duration.ZERO)
    fun awaitTermination(timeout: Duration): Boolean {
        require(!timeout.isNegative)
        check(!service.isCurrent(control)) { "A task cannot wait for itself" }
        return control.finished.await(timeout.toNanos(), TimeUnit.NANOSECONDS)
    }
    override fun close() { cancel() }
}

/** Fixed-delay repeats never overlap. Cancellation cannot be undone by a finishing invocation. */
class TaskService @JvmOverloads constructor(workers: Int = 2, private val maxTasks: Int = 4096,
    private val onError: (Task, Throwable) -> Unit = { task, error ->
        System.getLogger(TaskService::class.java.name).log(System.Logger.Level.WARNING, "Task ${task.name} failed: ${error.javaClass.simpleName}")
    }) : AutoCloseable {
    internal class Definition(task: Task) {
        val name = task.name
        val delay = task.delay
        val interval = task.interval
        val execution = task.execution
        val runnable = task.runnable
        init { require(name.isNotBlank()); nanos(delay, true); interval?.let { nanos(it, false) } }
        val type get() = if (interval == null) TaskType.DELAY else TaskType.REPEAT
    }
    internal class Control(val id: Long, val task: Task, val definition: Definition) {
        lateinit var handle: TaskHandle
        val finished = CountDownLatch(1)
        var active = true
        var state = TaskState.SCHEDULED
        var generation = 0L
        var timer: ScheduledFuture<*>? = null
        var work: Future<*>? = null
        var thread: Thread? = null
        var requested: Duration? = null
        var requestedAt = 0L
        var nextRunAt: Instant? = null
        var runs = 0L
        var failures = 0L
        var lastFailure: Throwable? = null
    }
    init { require(workers > 0 && maxTasks > 0) }
    private val lock = Any()
    private val sequence = AtomicLong()
    private val controls = IdentityHashMap<Task, Control>()
    private val timer = ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().name("cerberus-timer-", 0).factory()).apply {
        removeOnCancelPolicy = true; executeExistingDelayedTasksAfterShutdownPolicy = false
    }
    private fun worker(size: Int, name: String) = ThreadPoolExecutor(size, size, 0L, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(maxTasks), Thread.ofPlatform().daemon().name(name, 0).factory())
    private val async = worker(workers, "cerberus-task-")
    private val serial = worker(1, "cerberus-serial-")
    private var closed = false

    /** Original convenience signature is retained; use scheduleTask for a typed handle. */
    fun schedule(delay: Duration = Duration.ZERO, interval: Duration? = null, action: () -> Unit): AutoCloseable {
        val builder = Task.builder().name("task-${sequence.incrementAndGet()}").runnable(Runnable(action))
        if (interval != null) builder.interval(interval).initialDelay(delay) else builder.delay(delay)
        return scheduleTask(builder.build())
    }
    fun scheduleTask(task: Task): TaskHandle {
        val definition = Definition(task)
        return synchronized(lock) { register(task, definition) }
    }
    private fun register(task: Task, definition: Definition): TaskHandle {
        check(!closed) { "Task service is closed" }
        controls[task]?.let {
            check(it.active) { "Cancelled task is still running; wait for termination before scheduling it again" }
            return it.handle
        }
        check(controls.size < maxTasks) { "Task limit reached" }
        val control = Control(sequence.incrementAndGet(), task, definition)
        control.handle = TaskHandle(this, control)
        controls[task] = control
        plan(control, control.definition.delay)
        return control.handle
    }
    fun hasTask(task: Task): Boolean = synchronized(lock) { controls[task]?.active == true }
    fun rescheduleTask(task: Task): TaskHandle {
        val definition = Definition(task)
        return synchronized(lock) {
            val current = controls[task]
            if (current == null) register(task, definition) else {
                check(replan(current, current.definition.delay)) { "Cancelled task is still running" }; current.handle
            }
        }
    }
    fun runTask(task: Task): TaskHandle {
        val definition = Definition(task)
        return synchronized(lock) {
            val handle = controls[task]?.handle ?: register(task, definition)
            check(handle.runNow()) { "Task is cancelled" }; handle
        }
    }
    fun removeTask(task: Task): Boolean = synchronized(lock) { controls[task]?.let { cancel(it, true) } ?: false }
    fun snapshots(): List<TaskSnapshot> = synchronized(lock) { controls.values.map(::snapshot).sortedBy { it.id } }
    internal fun snapshot(control: Control): TaskSnapshot = synchronized(lock) {
        TaskSnapshot(control.id, control.definition.name, control.definition.execution, control.definition.type, control.state,
            control.runs, control.failures, control.lastFailure, control.nextRunAt, control.thread != null)
    }
    internal fun isCurrent(control: Control) = synchronized(lock) { control.thread === Thread.currentThread() }
    internal fun replan(control: Control, delay: Duration): Boolean = synchronized(lock) {
        nanos(delay, true)
        if (closed || !control.active) return@synchronized false
        control.generation++
        control.timer?.cancel(false); control.timer = null
        if (control.thread != null) {
            control.requested = delay; control.requestedAt = System.nanoTime()
        } else {
            discardQueued(control)
            plan(control, delay)
        }
        true
    }
    internal fun cancel(control: Control, interrupt: Boolean): Boolean = synchronized(lock) {
        if (!control.active) return@synchronized false
        control.active = false; control.state = TaskState.CANCELLED; control.nextRunAt = null; control.requested = null
        control.generation++; control.timer?.cancel(false); control.timer = null
        control.work?.cancel(interrupt); discardQueued(control)
        if (control.thread == null) finish(control)
        true
    }
    private fun executor(control: Control) = if (control.definition.execution == TaskExecution.SERIAL) serial else async
    private fun discardQueued(control: Control) {
        if (control.thread == null) {
            control.work?.cancel(false)
            (control.work as? Runnable)?.let { executor(control).remove(it) }
            control.work = null
        }
    }
    /** All control mutations take lock; user callbacks always run outside it. */
    private fun plan(control: Control, delay: Duration) {
        control.state = TaskState.SCHEDULED; control.nextRunAt = Instant.now().plus(delay)
        val generation = control.generation
        control.timer = timer.schedule({ dispatch(control, generation) }, delay.toNanos(), TimeUnit.NANOSECONDS)
    }
    private fun dispatch(control: Control, generation: Long) {
        var error: Throwable? = null
        synchronized(lock) {
            if (closed || !control.active || control.generation != generation) return
            control.state = TaskState.QUEUED; control.nextRunAt = null; control.timer = null
            try { control.work = executor(control).submit { execute(control, generation) } }
            catch (failure: RejectedExecutionException) {
                error = failure; control.lastFailure = failure; control.failures++; control.state = TaskState.FAILED
                control.active = false; finish(control)
            }
        }
        error?.let { report(control.task, it) }
    }
    private fun execute(control: Control, generation: Long) {
        synchronized(lock) {
            if (closed || !control.active || control.generation != generation) return
            control.thread = Thread.currentThread(); control.state = TaskState.RUNNING; control.runs++
        }
        var failure: Throwable? = null
        var repeat = false
        try { control.definition.runnable.run() } catch (error: Throwable) { failure = error }
        // The predicate belongs to this invocation too, so cancellation can still interrupt it.
        if (failure !is Error && synchronized(lock) { control.active && control.definition.interval != null && control.requested == null }) {
            try { repeat = control.task.shouldReschedule() } catch (error: Throwable) { failure = error }
        }
        failure?.let { if (it !is InterruptedException || !control.handle.isCancelled) report(control.task, it) }
        synchronized(lock) {
            control.thread = null; control.work = null
            if (failure != null && !(failure is InterruptedException && !control.active)) {
                control.lastFailure = failure; control.failures++
            }
            when {
                !control.active || closed -> finish(control)
                failure is Error -> { control.state = TaskState.FAILED; control.active = false; finish(control) }
                control.requested != null -> {
                    val elapsed = (System.nanoTime() - control.requestedAt).coerceAtLeast(0)
                    val remaining = (control.requested!!.toNanos() - elapsed).coerceAtLeast(0)
                    control.requested = null; plan(control, Duration.ofNanos(remaining))
                }
                repeat -> plan(control, control.definition.interval!!)
                else -> { control.state = if (failure == null) TaskState.COMPLETED else TaskState.FAILED; control.active = false; finish(control) }
            }
        }
    }
    private fun finish(control: Control) {
        if (controls[control.task] === control) controls.remove(control.task)
        control.finished.countDown()
    }
    private fun report(task: Task, error: Throwable) {
        try { onError(task, error) } catch (_: Throwable) { /* Reporting must not terminate the scheduler. */ }
    }
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true; controls.values.toList().forEach { cancel(it, true) }
        }
        timer.shutdownNow(); async.shutdownNow(); serial.shutdownNow()
    }
}

private fun nanos(value: Duration, zero: Boolean): Long {
    require(!value.isNegative && (zero || !value.isZero)) { "Invalid task delay or interval" }
    return value.toNanos() // reject overflow instead of silently saturating
}
