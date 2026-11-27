package labs.cerberus.ts

import labs.cerberus.ts.query.*
import org.junit.jupiter.api.Test
import kotlin.test.*
import kotlin.random.Random

class ProtocolTest {
    @Test fun `all protocol escapes including bell and actual vertical tab round trip`() {
        val raw = "\\/ |\u0007\b\u000c\n\r\t\u000b Grüße 日本 = ✅"
        assertEquals("\\\\\\/\\s\\p\\a\\b\\f\\n\\r\\t\\v\\sGrüße\\s日本\\s=\\s✅", QueryCodec.encode(raw))
        assertEquals(raw, QueryCodec.decode(QueryCodec.encode(raw)))
        assertEquals("\\s", QueryCodec.decode("\\\\s"))
        assertFailsWith<IllegalArgumentException> { QueryCodec.decode("trailing\\") }
        assertFailsWith<IllegalArgumentException> { QueryCodec.decode("\\x") }
    }

    @Test fun `random escaped values survive structural parsing`() {
        val random = Random(42)
        val chars = "abcXYZ=äö\\/ |\u0007\b\u000c\n\r\t\u000b"
        repeat(1000) {
            val original = (0 until random.nextInt(100)).map { chars[random.nextInt(chars.length)] }.joinToString("")
            assertEquals(listOf(mapOf("value" to original)), QueryCodec.rows("value=${QueryCodec.encode(original)}"))
        }
    }

    @Test fun `rows preserve equals empty fields and do not inherit missing properties`() {
        assertEquals(listOf(mapOf("uid" to "abc==", "name" to "one two", "empty" to ""), mapOf("id" to "2")),
            QueryCodec.rows("uid=abc== name=one\\stwo empty=|id=2"))
        assertFails { QueryCodec.rows("id=1 id=2") }
    }

    @Test fun `commands encode values and reject structural injection`() {
        val query = command("sendtextmessage", "msg" to "x\nquit|y z")
        assertEquals("sendtextmessage msg=x\\nquit\\py\\sz", query.wire())
        assertEquals("QueryCommand(sendtextmessage)", query.toString())
        assertFails { command("quit\nlogin") }
        assertFails { command("login", "x y" to "z") }
        assertFails { QueryCommand("clientlist", options = setOf("uid\nquit")) }
        assertEquals("clientmove clid=1 cid=2|clid=3 cid=4 -continueonerror",
            QueryCommand("clientmove", mapOf("clid" to "1", "cid" to "2"), setOf("continueonerror"),
                listOf(mapOf("clid" to "3", "cid" to "4"))).wire())
    }

    @Test fun `configuration rejects TS6 raw and redacts secrets`() {
        assertFails { ConnectionConfig(transport = QueryTransport.RAW, password = "secret") }
        val config = ConnectionConfig(password = "secret", apiKey = "private")
        assertEquals(10022, config.port)
        assertFalse(config.toString().contains("secret"))
        assertFalse(config.toString().contains("private"))
    }
}
