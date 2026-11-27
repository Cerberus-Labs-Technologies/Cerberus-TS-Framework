package labs.cerberus.ts.voice

/** Voice/audio uses a separate client protocol, never the ServerQuery connection. */
interface VoiceProvider {
    val id: String
    fun connect(host: String, port: Int, identity: String, nickname: String, password: String? = null): VoiceConnection
}
interface VoiceConnection : AutoCloseable {
    val isConnected: Boolean
    val clientId: Int
    var paused: Boolean
    fun move(channelId: Int, password: String? = null)
    /** One 20 ms stereo Opus frame at 48 kHz. Applies backpressure when the bounded buffer fills. */
    fun sendOpus(packet: ByteArray)
    fun clearAudio()
    val bufferedPackets: Int
    val supportsPresentation: Boolean get() = false
    fun onOpus(listener: (clientId: Int, packet: ByteArray) -> Unit): AutoCloseable
    fun privateMessage(clientId: Int, message: String)
    fun setDescription(description: String) { throw UnsupportedOperationException("Descriptions not supported by this voice provider") }
    /** Upload own avatar and publish its MD5 flag. Encoded image must fit the server's avatar size limit. */
    fun setAvatar(image: ByteArray) { throw UnsupportedOperationException("Avatars not supported by this voice provider") }
}

class VoiceService {
    private val providers = java.util.concurrent.ConcurrentHashMap<String, VoiceProvider>()
    fun register(provider: VoiceProvider): AutoCloseable {
        check(providers.putIfAbsent(provider.id, provider) == null)
        return AutoCloseable { providers.remove(provider.id, provider) }
    }
    fun provider(id: String): VoiceProvider = providers[id] ?: throw UnsupportedOperationException(
        "No voice provider '$id' installed.")
}
