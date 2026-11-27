package labs.cerberus.ts

import labs.cerberus.ts.event.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class PaperCancellableEvent : Cancellable { override var cancelled = false }

class PrioritizedPaperListener(val calls: MutableList<String>) : Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    fun lowest(event: PaperCancellableEvent) { calls += "lowest"; event.cancelled = true }
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun low(event: PaperCancellableEvent) { calls += "skipped" }
    @EventHandler
    fun normal(event: PaperCancellableEvent) { calls += "normal:${event.cancelled}" }
    @EventHandler(priority = EventPriority.HIGH)
    fun high(event: PaperCancellableEvent) { calls += "high"; event.cancelled = false }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun highest(event: PaperCancellableEvent) { calls += "highest" }
    @EventHandler(priority = EventPriority.MONITOR)
    fun monitor(event: PaperCancellableEvent) { calls += "monitor:${event.cancelled}" }
}

class InvalidPaperListener : Listener {
    var calls = 0
    @EventHandler fun valid(event: PaperCancellableEvent) { calls++ }
    @EventHandler fun invalid(): Int = 1
}

class PaperEventTest {
    @Test fun `Paper priorities and ignoreCancelled follow Paper semantics`() {
        val bus = EventBus { throw AssertionError(it) }
        val calls = mutableListOf<String>()
        val registration = bus.register(PrioritizedPaperListener(calls))
        bus.publish(PaperCancellableEvent())
        assertEquals(listOf("lowest", "normal:true", "high", "highest", "monitor:false"), calls)
        registration.close(); calls.clear()
        bus.publish(PaperCancellableEvent())
        assertTrue(calls.isEmpty())
    }

    @Test fun `invalid event handler registration rolls back other methods`() {
        val listener = InvalidPaperListener()
        val bus = EventBus()
        assertFailsWith<IllegalArgumentException> { bus.register(listener) }
        bus.publish(PaperCancellableEvent())
        assertEquals(0, listener.calls)
    }
}
