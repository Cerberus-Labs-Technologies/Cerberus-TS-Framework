package labs.cerberus.ts.event

import labs.cerberus.ts.query.QueryNotification
import labs.cerberus.ts.service.DatabaseService
import labs.cerberus.ts.service.MethodInvocation
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

interface Cancellable { var cancelled: Boolean }
enum class BotState { NEW, LOADING, CONNECTING, CONNECTED, DISCONNECTED, STOPPING, STOPPED }
data class StateChanged(val previous: BotState, val current: BotState)
data class QueryEvent(val notification: QueryNotification)
data class TextMessage(val clientId: Int, val uniqueId: String?, val nickname: String,
    val targetMode: Int, val message: String)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Subscribe(val order: Int = 0, val receiveCancelled: Boolean = false)

interface Listener
enum class EventPriority { LOWEST, LOW, NORMAL, HIGH, HIGHEST, MONITOR }

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class EventHandler(val priority: EventPriority = EventPriority.NORMAL, val ignoreCancelled: Boolean = false)

class EventBus(database: DatabaseService? = null, private val onError: (Throwable) -> Unit = {
    System.getLogger(EventBus::class.java.name).log(System.Logger.Level.WARNING, "Event listener failed: ${it.javaClass.simpleName}")
}) {
    private val invocation = MethodInvocation(database)
    private data class Entry(val type: Class<*>, val order: Int, val sequence: Long,
        val receiveCancelled: Boolean, val callback: (Any) -> Unit)
    private val entries = CopyOnWriteArrayList<Entry>()
    private val sequence = AtomicLong()

    fun <T : Any> subscribe(type: Class<T>, order: Int = 0, receiveCancelled: Boolean = false,
        listener: (T) -> Unit): AutoCloseable {
        val entry = Entry(type, order, sequence.getAndIncrement(), receiveCancelled) { listener(type.cast(it)) }
        entries.add(entry)
        return AutoCloseable { entries.remove(entry) }
    }

    inline fun <reified T : Any> subscribe(order: Int = 0, noinline listener: (T) -> Unit): AutoCloseable =
        subscribe(T::class.java, order, listener = listener)

    fun register(listener: Any, includeEventHandlers: Boolean = true, active: () -> Boolean = { true }): AutoCloseable {
        val registrations = mutableListOf<AutoCloseable>()
        try {
            listener.javaClass.methods.filter { it.isAnnotationPresent(Subscribe::class.java) ||
                (includeEventHandlers && it.isAnnotationPresent(EventHandler::class.java)) }.forEach { method ->
                require(method.parameterCount == 1 && method.returnType == Void.TYPE) { "Listener must accept one event and return Unit" }
                val annotation = method.getAnnotation(Subscribe::class.java)
                val handler = method.getAnnotation(EventHandler::class.java)
                require(annotation == null || handler == null) { "Use one event annotation per method" }
                require(!java.lang.reflect.Modifier.isStatic(method.modifiers)) { "Event handler must be an instance method" }
                require(!method.parameterTypes[0].isPrimitive) { "Event handler requires an event object" }
                check(method.trySetAccessible()) { "Event handler is inaccessible" }
                invocation.validate(method)
                @Suppress("UNCHECKED_CAST")
                registrations += subscribe(method.parameterTypes[0] as Class<Any>,
                    annotation?.order ?: (handler!!.priority.ordinal * 100),
                    annotation?.receiveCancelled ?: !handler!!.ignoreCancelled) { if (active()) invocation.invoke(listener, method, arrayOf(it)) }
            }
        } catch (e: Exception) { registrations.asReversed().forEach(AutoCloseable::close); throw e }
        return AutoCloseable { registrations.asReversed().forEach(AutoCloseable::close) }
    }

    fun publish(event: Any) {
        entries.sortedWith(compareBy<Entry> { it.order }.thenBy { it.sequence }).forEach {
            if (it.type.isInstance(event) && (event !is Cancellable || !event.cancelled || it.receiveCancelled)) {
                try { it.callback(event) } catch (e: Exception) { onError(e.cause ?: e) }
            }
        }
    }
}
