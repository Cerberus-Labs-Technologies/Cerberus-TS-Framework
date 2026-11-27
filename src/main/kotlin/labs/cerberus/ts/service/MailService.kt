package labs.cerberus.ts.service

import jakarta.activation.DataHandler
import jakarta.activation.DataSource
import jakarta.mail.*
import jakarta.mail.internet.*
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Date
import java.util.Properties
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

class MailAttachment private constructor(val name: String, val contentType: String, private val input: () -> InputStream) {
    init {
        require(name.isNotBlank() && name.none { it == '\r' || it == '\n' || it == '\u0000' })
        require(contentType.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Invalid attachment content type" }
        ContentType(contentType)
    }
    internal fun source() = object : DataSource {
        override fun getInputStream() = input()
        override fun getOutputStream(): java.io.OutputStream = throw UnsupportedOperationException("Read-only attachment")
        override fun getContentType() = this@MailAttachment.contentType
        override fun getName() = this@MailAttachment.name
    }
    companion object {
        @JvmStatic @JvmOverloads fun fromPath(name: String, file: Path, contentType: String = "application/octet-stream"): MailAttachment {
            require(Files.isRegularFile(file) && Files.isReadable(file)) { "Attachment must be a readable regular file" }
            return MailAttachment(name, contentType) { Files.newInputStream(file) }
        }
        @JvmStatic @JvmOverloads fun fromFile(name: String, file: java.io.File, contentType: String = "application/octet-stream") =
            fromPath(name, file.toPath(), contentType)
        @JvmStatic @JvmOverloads fun fromBytes(name: String, bytes: ByteArray, contentType: String = "application/octet-stream"): MailAttachment {
            val snapshot = bytes.copyOf()
            return MailAttachment(name, contentType) { ByteArrayInputStream(snapshot) }
        }
        @JvmStatic fun fromSource(name: String, source: DataSource) = MailAttachment(name, source.contentType) { source.inputStream }
    }
}

class MailMessage @JvmOverloads constructor(to: List<String>, val subject: String, val text: String,
    cc: List<String> = emptyList(), bcc: List<String> = emptyList(), val html: String? = null,
    attachments: List<MailAttachment> = emptyList(), headers: Map<String, String> = emptyMap()) {
    val to: List<String> = java.util.List.copyOf(to)
    val cc: List<String> = java.util.List.copyOf(cc)
    val bcc: List<String> = java.util.List.copyOf(bcc)
    val attachments: List<MailAttachment> = java.util.List.copyOf(attachments)
    val headers: Map<String, String> = java.util.Map.copyOf(headers)
    init {
        require((to + cc + bcc).isNotEmpty()) { "Mail needs at least one recipient" }
        (to + cc + bcc).forEach(::mailAddress)
        require(subject.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Invalid mail subject" }
        headers.forEach { (name, value) ->
            require(name.matches(Regex("[A-Za-z0-9-]+")) && name.lowercase() !in setOf("to", "cc", "bcc", "subject", "mime-version") && !name.startsWith("content-", true)) { "Reserved or invalid mail header" }
            require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Invalid mail header value" }
            if (name.equals("from", true) || name.equals("sender", true)) mailAddress(value)
        }
    }
    companion object { @JvmStatic fun builder() = MailBuilder() }
}

class MailBuilder {
    private val to = mutableListOf<String>(); private val cc = mutableListOf<String>(); private val bcc = mutableListOf<String>()
    val subject = StringBuilder(); val body = StringBuilder()
    private var html: String? = null
    private val attachments = mutableListOf<MailAttachment>(); private val headers = linkedMapOf<String, String>()
    fun to(recipient: String) = apply { if (recipient !in to) to += recipient }
    fun toCC(recipient: String) = apply { if (recipient !in cc) cc += recipient }
    fun toBCC(recipient: String) = apply { if (recipient !in bcc) bcc += recipient }
    fun addSubjectText(text: String) = apply { subject.append(text) }
    fun addText(text: String) = apply { body.append(text) }
    fun html(text: String) = apply { html = text }
    fun attach(attachment: MailAttachment) = apply { if (attachment !in attachments) attachments += attachment }
    fun header(name: String, value: String?) = apply {
        headers.keys.removeIf { it.equals(name, true) }
        if (value != null) headers[name] = value
    }
    fun build() = MailMessage(to, subject.toString(), body.toString(), cc, bcc, html, attachments, headers)
}

private fun mailAddress(value: String): InternetAddress {
    require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Invalid mail address" }
    return InternetAddress(value, true).also { address ->
        address.validate()
        address.personal?.let { address.setPersonal(it, "UTF-8") }
    }
}

fun interface MailTransport {
    fun send(message: MailMessage)
    fun dispatch(message: MailMessage): CompletableFuture<Void> = CompletableFuture.runAsync { send(message) }
}
enum class SmtpTls { STARTTLS, IMPLICIT, NONE }

/** Sending is synchronous; dispatch provides the asynchronous transport contract used by Jeak. */
class MailService @JvmOverloads constructor(host: String, port: Int = 587, private val from: String,
    username: String? = null, password: String? = null, implicitTls: Boolean = false,
    tls: SmtpTls = if (implicitTls) SmtpTls.IMPLICIT else SmtpTls.STARTTLS,
    extraProperties: Map<String, String> = emptyMap()) : NotificationChannel, MailTransport, AutoCloseable {
    private val session: Session
    private val closed = AtomicBoolean()
    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<Void>>()
    private val active = ConcurrentHashMap.newKeySet<Transport>()
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS, ArrayBlockingQueue(128),
        Thread.ofPlatform().daemon().name("cerberus-mail").factory())
    init {
        mailAddress(from)
        val properties = Properties().apply {
            setProperty("mail.smtp.host", host); setProperty("mail.smtp.port", "$port")
            setProperty("mail.smtp.auth", "${username != null}")
            username?.let { setProperty("mail.smtp.user", it) }; password?.let { setProperty("mail.smtp.pass", it) }
            setProperty("mail.smtp.connectiontimeout", "10000"); setProperty("mail.smtp.timeout", "10000"); setProperty("mail.smtp.writetimeout", "10000")
            setProperty("mail.smtp.ssl.checkserveridentity", "true")
            setProperty("mail.smtp.ssl.enable", "${tls == SmtpTls.IMPLICIT}")
            setProperty("mail.smtp.starttls.enable", "${tls == SmtpTls.STARTTLS}")
            setProperty("mail.smtp.starttls.required", "${tls == SmtpTls.STARTTLS}")
            putAll(extraProperties)
            if ("mail.smtp.starttls.enable" in extraProperties && "mail.smtp.starttls.required" !in extraProperties)
                setProperty("mail.smtp.starttls.required", getProperty("mail.smtp.starttls.enable"))
        }
        require(properties.getProperty("mail.smtp.host").isNotBlank()) { "SMTP host must not be blank" }
        require(properties.getProperty("mail.smtp.port").toIntOrNull()?.let { it in 1..65535 } == true) { "Invalid SMTP port" }
        setOf("mail.smtp.auth", "mail.smtp.ssl.enable", "mail.smtp.ssl.checkserveridentity", "mail.smtp.starttls.enable", "mail.smtp.starttls.required").forEach { key ->
            val value = properties.getProperty(key).lowercase(java.util.Locale.ROOT).toBooleanStrictOrNull()
                ?: throw IllegalArgumentException("SMTP property must be boolean: $key")
            properties.setProperty(key, value.toString())
        }
        setOf("mail.smtp.connectiontimeout", "mail.smtp.timeout", "mail.smtp.writetimeout").forEach { key ->
            require(properties.getProperty(key).toIntOrNull()?.let { it >= -1 } == true) { "Invalid SMTP timeout property: $key" }
        }
        val authenticated = properties.getProperty("mail.smtp.auth").toBoolean()
        val user = properties.getProperty("mail.smtp.user")
        val pass = properties.getProperty("mail.smtp.pass")
        require(!authenticated || !user.isNullOrBlank() && pass != null) { "SMTP authentication needs username and password" }
        session = Session.getInstance(properties, if (!authenticated) null else object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(user, pass)
        })
    }
    internal fun mime(message: MailMessage): MimeMessage = MimeMessage(session).apply {
        setFrom(mailAddress(this@MailService.from))
        fun recipients(type: Message.RecipientType, values: List<String>) {
            if (values.isNotEmpty()) setRecipients(type, values.map(::mailAddress).toTypedArray())
        }
        recipients(Message.RecipientType.TO, message.to); recipients(Message.RecipientType.CC, message.cc); recipients(Message.RecipientType.BCC, message.bcc)
        setSubject(message.subject, "UTF-8"); sentDate = Date()
        val textPart = MimeBodyPart().apply { setText(message.text, "UTF-8") }
        val contentPart = if (message.html == null) textPart else MimeBodyPart().apply {
            setContent(MimeMultipart("alternative").apply {
                addBodyPart(textPart); addBodyPart(MimeBodyPart().apply { setText(message.html, "UTF-8", "html") })
            })
        }
        if (message.attachments.isEmpty()) {
            if (message.html == null) setText(message.text, "UTF-8") else setContent(contentPart.content as Multipart)
        } else setContent(MimeMultipart("mixed").apply {
            addBodyPart(contentPart)
            message.attachments.forEach { attachment -> addBodyPart(MimeBodyPart().apply {
                dataHandler = DataHandler(attachment.source()); fileName = attachment.name; disposition = Part.ATTACHMENT
            }) }
        })
        message.headers.forEach { (key, value) -> when {
            key.equals("from", true) -> setFrom(mailAddress(value))
            key.equals("sender", true) -> setSender(mailAddress(value))
            else -> setHeader(key, value)
        } }
        saveChanges()
    }
    override fun send(message: MailMessage) {
        check(!closed.get()) { "Mail transport is closed" }
        val content = mime(message)
        val transport = session.getTransport("smtp")
        active += transport
        try {
            transport.use {
                check(!closed.get()) { "Mail transport is closed" }
                it.connect()
                check(!closed.get()) { "Mail transport is closed" }
                it.sendMessage(content, content.allRecipients)
            }
        } finally { active -= transport }
    }
    override fun send(notification: Notification) = send(MailMessage(listOf(notification.recipient), notification.subject, notification.longText))
    override fun dispatch(message: MailMessage): CompletableFuture<Void> {
        val result = CompletableFuture<Void>(); pending += result
        result.whenComplete { _, _ -> pending -= result }
        try {
            check(!closed.get()) { "Mail transport is closed" }
            executor.execute {
                try { if (!result.isCancelled) { send(message); result.complete(null) } }
                catch (e: Exception) { result.completeExceptionally(e) }
            }
        } catch (e: Exception) { result.completeExceptionally(e) }
        return result
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending.forEach { it.completeExceptionally(CancellationException("Mail transport closed")) }
        executor.shutdownNow()
        var failure: Exception? = null
        active.toList().forEach { transport ->
            try { transport.close() } catch (error: Exception) { if (failure == null) failure = error else failure.addSuppressed(error) }
        }
        failure?.let { throw it }
    }
}
