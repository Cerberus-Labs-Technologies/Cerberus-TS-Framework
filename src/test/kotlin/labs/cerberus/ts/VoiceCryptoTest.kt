package labs.cerberus.ts

import com.github.manevolent.ts3j.protocol.Packet
import com.github.manevolent.ts3j.protocol.ProtocolRole
import com.github.manevolent.ts3j.protocol.header.ClientPacketHeader
import com.github.manevolent.ts3j.protocol.packet.PacketBody2Command
import com.github.manevolent.ts3j.protocol.packet.PacketBodyType
import com.github.manevolent.ts3j.protocol.packet.transformation.PacketTransformation
import labs.cerberus.ts.voice.PacketAead
import org.bouncycastle.crypto.InvalidCipherTextException
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.*

class VoiceCryptoTest {
    @Test fun `packet encryption supports identical retransmissions and rejects tampering`() {
        val iv = ByteArray(64) { it.toByte() }
        val cipher = PacketAead(PacketTransformation(iv, ByteArray(8)))
        val header = ClientPacketHeader().apply { packetId = 123; generation = 2; type = PacketBodyType.COMMAND }
        val packet = Packet(ProtocolRole.CLIENT, header).apply { body = PacketBody2Command(ProtocolRole.CLIENT, "test value=hello") }
        val first = cipher.encrypt(packet).array()
        assertContentEquals(first, cipher.encrypt(packet).array())
        val expected = packet.writeBody(ByteBuffer.allocate(packet.body.size)).array()
        assertContentEquals(expected, cipher.decrypt(header, ByteBuffer.wrap(first), packet.body.size))
        // Sending again after receiving must not carry cipher state across directions.
        assertContentEquals(first, cipher.encrypt(packet).array())
        first[first.lastIndex] = (first.last().toInt() xor 1).toByte()
        assertFailsWith<InvalidCipherTextException> { cipher.decrypt(header, ByteBuffer.wrap(first), packet.body.size) }
        val different = header.clone().apply { packetId++ }
        assertFalse(cipher.computeParameters(header).key.contentEquals(cipher.computeParameters(different).key))
    }
}
