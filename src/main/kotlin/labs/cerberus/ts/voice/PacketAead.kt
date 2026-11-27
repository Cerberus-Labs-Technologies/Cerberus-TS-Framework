package labs.cerberus.ts.voice

import com.github.manevolent.ts3j.protocol.Packet
import com.github.manevolent.ts3j.protocol.header.PacketHeader
import com.github.manevolent.ts3j.protocol.packet.transformation.PacketTransformation
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.EAXBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Adapted from TS3J's PacketTransformation (Manevolent, Apache-2.0).
 * Keep TS3J's wire format/key derivation; use an independent AEAD operation per datagram.
 * Reliable UDP retransmits identical packets. A shared modern BC EAX instance rejects
 * their repeated key/nonce; cipher state must not be shared across datagrams or directions.
 */
internal class PacketAead(private val delegate: PacketTransformation) : PacketTransformation(ByteArray(20), delegate.fakeMac) {
    override fun computeParameters(header: PacketHeader) = delegate.computeParameters(header)
    override fun decrypt(header: PacketHeader, buffer: ByteBuffer, dataLen: Int): ByteArray = delegate.decrypt(header, buffer, dataLen)
    override fun encrypt(packet: Packet): ByteBuffer {
        val parameters = computeParameters(packet.header)
        val header = packet.writeHeader(ByteBuffer.allocate(packet.header.size).order(ByteOrder.BIG_ENDIAN)).array().copyOfRange(8, packet.header.size)
        val body = packet.writeBody(ByteBuffer.allocate(packet.body.size).order(ByteOrder.BIG_ENDIAN)).array()
        val cipher = EAXBlockCipher(AESEngine.newInstance())
        cipher.init(true, AEADParameters(KeyParameter(parameters.key), 64, parameters.value, header))
        val encrypted = ByteArray(cipher.getOutputSize(body.size))
        val count = cipher.processBytes(body, 0, body.size, encrypted, 0)
        val length = count + cipher.doFinal(encrypted, count)
        encrypted.copyInto(packet.header.mac, 0, length - 8, length)
        return ByteBuffer.allocate(packet.header.size + length - 8)
            .put(encrypted, length - 8, 8).put(header).put(encrypted, 0, length - 8)
    }
}
