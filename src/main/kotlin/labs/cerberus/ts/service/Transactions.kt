package labs.cerberus.ts.service

import java.util.concurrent.locks.ReentrantReadWriteLock
import java.lang.reflect.Method
import java.lang.reflect.InvocationTargetException
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Applies to framework-invoked event listeners and REST controller methods. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Transactional(val unit: String = "default", val orm: Boolean = true)

class RollbackOnlyException(cause: Throwable) : IllegalStateException("Nested transaction failed; transaction rolled back", cause)

/** One resource/transaction per thread. A caught nested failure still prevents an outer commit. */
internal class TransactionRunner<R : Any>(private val open: () -> R, private val begin: (R) -> Unit,
    private val commit: (R) -> Unit, private val rollback: (R) -> Unit, private val release: (R) -> Unit) : AutoCloseable {
    private class State<R>(val resource: R) {
        var failure: Throwable? = null
        val callbacks = mutableListOf<Pair<Throwable, (Throwable) -> Unit>>()
    }
    private val local = ThreadLocal<State<R>>()
    private val lock = ReentrantReadWriteLock(true)
    private var closed = false
    val activeOnCurrentThread: Boolean get() = local.get() != null

    fun <T> execute(action: (R) -> T): T {
        var failure: Throwable? = null
        var result: T? = null
        val callbacks = mutableListOf<Pair<Throwable, (Throwable) -> Unit>>()
        lock.read {
            check(!closed) { "Transaction source is closed" }
            local.get()?.let { state ->
                state.failure?.let { throw RollbackOnlyException(it) }
                try { return action(state.resource) }
                catch (error: Throwable) { state.failure = error; throw error }
            }
            val resource = open()
            val state = State(resource)
            var begun = false
            var committed = false
            try {
                begun = true; begin(resource); local.set(state)
                result = action(resource)
                state.failure?.let { throw RollbackOnlyException(it) }
                commit(resource); committed = true
            } catch (error: Throwable) {
                failure = error
                if (begun && !committed) try { rollback(resource) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            } finally {
                local.remove()
                try { release(resource) } catch (cleanup: Throwable) {
                    if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
                }
                callbacks += state.callbacks
            }
        }
        // Nested recovery callbacks observe the completed rollback and released connection.
        callbacks.forEach { (error, callback) ->
            try { callback(error) } catch (callbackError: Throwable) {
                if (failure == null) failure = callbackError else failure.addSuppressed(callbackError)
            }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    fun <T> recover(action: (R) -> T, onError: (Throwable) -> Unit): T? = try { execute(action) }
    catch (error: Throwable) {
        val state = local.get()
        if (state != null) state.callbacks += error to onError
        else try { onError(error) } catch (callbackError: Throwable) { callbackError.addSuppressed(error); throw callbackError }
        null
    }

    override fun close() {
        check(local.get() == null) { "Cannot close a transaction source from its transaction" }
        lock.write { closed = true }
    }
}

/** Reflection entry points share annotation behavior; ordinary/self method calls are not intercepted. */
internal class MethodInvocation(private val database: DatabaseService? = null) {
    fun validate(method: Method) {
        method.getAnnotation(Transactional::class.java)?.let {
            require(it.unit.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*"))) { "Invalid transactional unit" }
            check(database != null) { "@Transactional requires a DatabaseService" }
        }
    }
    fun invoke(target: Any, method: Method, arguments: Array<out Any?>): Any? {
        val action = { try { method.invoke(target, *arguments) } catch (error: InvocationTargetException) { throw error.targetException } }
        val transaction = method.getAnnotation(Transactional::class.java) ?: return action()
        val service = checkNotNull(database) { "@Transactional requires a DatabaseService" }
        return if (transaction.orm) service.withEntityManager(transaction.unit) { action() }
        else service.transaction(transaction.unit) { action() }
    }
}
