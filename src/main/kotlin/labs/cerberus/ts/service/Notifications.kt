package labs.cerberus.ts.service

import labs.cerberus.ts.teamspeak.TeamSpeakServer
import java.time.Clock
import java.time.Instant
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

enum class Urgency(val level: Int) { DISMISSABLE(100), BASIC(1000), WARN(500000), ALERT(1000000) }
enum class Lifespan(val level: Int) { SHORT(100), SHORTER(500), BASIC(1000), LONGER(5000), LONG(10000), FOREVER(Int.MAX_VALUE) }
enum class NotificationRecipientKind { CLIENT_ID, IDENTITY }

data class Notification @JvmOverloads constructor(val recipient: String, val message: String,
    val subject: String = "Cerberus TS notification", val expiresAt: Instant? = null,
    val recipients: List<String> = listOf(recipient), val urgency: Int = Urgency.BASIC.level,
    val lifespan: Int = Lifespan.BASIC.level, val longText: String = message,
    val recipientKind: NotificationRecipientKind = NotificationRecipientKind.CLIENT_ID) {
    init { require(recipients.isNotEmpty() && recipients.all(String::isNotBlank) && urgency >= 0 && lifespan >= 0) }
    companion object { @JvmStatic fun builder() = NotificationBuilder() }
}

/** Builder recipients are persistent TeamSpeak UIDs, including users currently offline. */
class NotificationBuilder {
    private val recipients = linkedSetOf<String>()
    private var summary = "Cerberus TS notification"; private var shortText = ""; private var longText: String? = null
    private var urgency = Urgency.BASIC.level; private var lifespan = Lifespan.BASIC.level; private var expiresAt: Instant? = null
    fun addRecipient(uniqueId: String) = apply { require(uniqueId.isNotBlank()); recipients += uniqueId }
    fun addRecipient(client: labs.cerberus.ts.teamspeak.Client) = addRecipient(requireNotNull(client.uniqueId))
    fun summary(value: String) = apply { summary = value }
    fun shortText(value: String) = apply { shortText = value }
    fun longText(value: String) = apply { longText = value }
    fun urgency(value: Int) = apply { require(value >= 0); urgency = value }
    fun urgency(value: Urgency) = urgency(value.level)
    fun lifespan(value: Int) = apply { require(value >= 0); lifespan = value }
    fun lifespan(value: Lifespan) = lifespan(value.level)
    fun expiresAt(value: Instant?) = apply { expiresAt = value }
    fun build(): Notification {
        require(recipients.isNotEmpty()) { "Notification needs recipients" }
        return Notification(recipients.first(), shortText, summary, expiresAt, java.util.List.copyOf(recipients), urgency, lifespan,
            longText?.takeIf(String::isNotEmpty) ?: shortText, NotificationRecipientKind.IDENTITY)
    }
    fun reset() = apply {
        recipients.clear(); summary = "Cerberus TS notification"; shortText = ""; longText = null
        urgency = Urgency.BASIC.level; lifespan = Lifespan.BASIC.level; expiresAt = null
    }
}

fun interface NotificationChannel {
    fun send(notification: Notification)
    fun available(): Boolean = true
}
data class NotificationFilter(val urgency: IntRange = 0..Int.MAX_VALUE, val lifespan: IntRange = 0..Int.MAX_VALUE) {
    init { require(!urgency.isEmpty() && !lifespan.isEmpty() && urgency.first >= 0 && lifespan.first >= 0) }
    fun accepts(notification: Notification) = notification.urgency in urgency && notification.lifespan in lifespan
}
/** Successful channel invocations, not recipient delivery receipts. Failures do not stop other channels. */
data class NotificationDispatch(val successfulChannels: Set<String>, val unavailableChannels: Set<String>, val failures: Map<String, Throwable>, val expired: Boolean = false)

class NotificationService @JvmOverloads constructor(private val server: TeamSpeakServer,
    private val profiles: ProfileService? = null, private val mail: MailTransports? = null,
    private val clock: Clock = Clock.systemUTC()) : AutoCloseable {
    private data class Registration(val channel: NotificationChannel, val filter: NotificationFilter)
    private val channels = ConcurrentHashMap<String, Registration>()
    private val closed = AtomicBoolean()
    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<NotificationDispatch>>()
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS, ArrayBlockingQueue(256),
        Thread.ofPlatform().daemon().name("cerberus-notifications").factory())
    init {
        register("teamspeak.message", NotificationFilter(Urgency.BASIC.level..Urgency.ALERT.level, Lifespan.SHORTER.level..Lifespan.LONGER.level),
            NotificationChannel { deliverVoice(it, false) })
        register("teamspeak.poke", NotificationFilter(Urgency.WARN.level..Urgency.ALERT.level, 0..Lifespan.SHORTER.level),
            NotificationChannel { deliverVoice(it, true) })
        if (profiles != null && mail != null) register("mail", NotificationFilter(Urgency.BASIC.level..Int.MAX_VALUE, Lifespan.SHORTER.level..Int.MAX_VALUE), object : NotificationChannel {
            override fun available() = mail.find("mail-notifications") != null
            override fun send(notification: Notification) {
                require(notification.recipientKind == NotificationRecipientKind.IDENTITY) { "Mail notification routing requires persistent UIDs" }
                val transport = mail.require("mail-notifications")
                val addresses = notification.recipients.mapNotNull { profiles.find(it)?.option("notifications:email") }.toSet()
                val failures = mutableListOf<Exception>()
                addresses.forEach { address ->
                    try { transport.send(MailMessage(listOf(address), "[Cerberus TS] ${notification.subject}", notification.longText)) }
                    catch (e: Exception) { failures += e }
                }
                throwIfFailures(failures)
            }
        })
    }
    fun register(id: String, channel: NotificationChannel): AutoCloseable = register(id, NotificationFilter(), channel)
    @Synchronized fun register(id: String, filter: NotificationFilter, channel: NotificationChannel): AutoCloseable {
        check(!closed.get()); require(id.isNotBlank())
        val registration = Registration(channel, filter)
        check(channels.putIfAbsent(id, registration) == null) { "Notification channel already registered" }
        return AutoCloseable { channels.remove(id, registration) }
    }
    fun send(channel: String, notification: Notification): Boolean {
        check(!closed.get())
        if (expired(notification)) return false
        val target = channels[channel]?.channel ?: error("Missing notification channel")
        if (!target.available()) return false
        target.send(snapshot(notification)); return true
    }
    fun dispatch(notification: Notification): CompletableFuture<NotificationDispatch> {
        val result = CompletableFuture<NotificationDispatch>(); pending += result
        result.whenComplete { _, _ -> pending -= result }
        try {
            check(!closed.get())
            val immutable = snapshot(notification)
            executor.execute {
                if (result.isCancelled) return@execute
                try { result.complete(dispatchNow(immutable)) } catch (e: Exception) { result.completeExceptionally(e) }
            }
        } catch (e: Exception) { result.completeExceptionally(e) }
        return result
    }
    private fun dispatchNow(notification: Notification): NotificationDispatch {
        if (expired(notification)) return NotificationDispatch(emptySet(), emptySet(), emptyMap(), true)
        val sent = linkedSetOf<String>(); val unavailable = linkedSetOf<String>(); val failures = linkedMapOf<String, Throwable>()
        channels.entries.sortedBy { it.key }.forEach { (name, entry) ->
            if (entry.filter.accepts(notification)) {
                try { if (entry.channel.available()) { entry.channel.send(notification); sent += name } else unavailable += name }
                catch (e: Exception) { failures[name] = e }
            }
        }
        return NotificationDispatch(sent, unavailable, failures)
    }
    private fun deliverVoice(notification: Notification, poke: Boolean) {
        val limit = if (poke) 100 else 1024
        val text = truncateUtf8(notification.message, limit)
        val failures = mutableListOf<Exception>()
        val targets = if (notification.recipientKind == NotificationRecipientKind.CLIENT_ID)
            notification.recipients.distinct().map { it.toInt() to null }
        else server.clients(refresh = true).filter { !it.isQueryClient && it.uniqueId in notification.recipients }.map { it.id to it.uniqueId }
        targets.forEach { (id, uid) ->
            try {
                // IDs can be reused after a disconnect; verify UID immediately before sending.
                if (uid == null || server.identity(id).uniqueId == uid) {
                    if (poke) server.poke(id, text) else server.privateMessage(id, text)
                }
            } catch (e: Exception) { failures += e }
        }
        throwIfFailures(failures)
    }
    private fun expired(notification: Notification) = notification.expiresAt?.let { !it.isAfter(clock.instant()) } == true
    private fun snapshot(value: Notification) = value.copy(recipients = java.util.List.copyOf(value.recipients))
    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending.forEach { it.completeExceptionally(CancellationException("Notification service closed")) }
        executor.shutdownNow(); channels.clear()
    }
    companion object {
        private fun throwIfFailures(failures: List<Exception>) {
            if (failures.isNotEmpty()) throw IllegalStateException("Notification delivery failed for ${failures.size} recipients").also { error -> failures.forEach(error::addSuppressed) }
        }
        internal fun truncateUtf8(text: String, bytes: Int): String {
            var used = 0; var end = 0
            while (end < text.length) {
                val cp = text.codePointAt(end)
                val width = when { cp <= 0x7f -> 1; cp <= 0x7ff -> 2; cp <= 0xffff -> 3; else -> 4 }
                if (used + width > bytes) break
                used += width; end += Character.charCount(cp)
            }
            return text.substring(0, end)
        }
    }
}
