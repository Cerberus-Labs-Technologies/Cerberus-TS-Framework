package labs.cerberus.ts

import labs.cerberus.ts.query.*
import labs.cerberus.ts.event.EventBus
import labs.cerberus.ts.teamspeak.TeamSpeakServer
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.*

class RawQueryTest {
    @Test fun `TS3 raw authenticates before server selection and registers events`() {
        val requests = mutableListOf<String>()
        WireFixture { socket ->
            val reader = socket.getInputStream().bufferedReader()
            val output = socket.getOutputStream()
            output.write("TS3\n\rWelcome to the TeamSpeak 3 ServerQuery interface\n\r".toByteArray()); output.flush()
            while (true) {
                val line = reader.readLine() ?: break
                requests += line
                when (line.substringBefore(' ')) {
                    "version" -> output.write("version=3.13.7\n".toByteArray())
                    "whoami" -> output.write("client_id=1 virtualserver_id=1\n".toByteArray())
                }
                output.write("error id=0 msg=ok\n".toByteArray()); output.flush()
            }
        }.use { fixture ->
            val config = ConnectionConfig(transport = QueryTransport.RAW, generation = ServerGeneration.TS3,
                host = "localhost", port = fixture.port, username = "admin", password = "a b", minimumCommandInterval = Duration.ZERO)
            TeamSpeakServer(config, EventBus()).use { server -> server.connect(); assertTrue(server.isConnected) }
            fixture.completion.get(3, TimeUnit.SECONDS)
            assertEquals("login client_login_name=admin client_login_password=a\\sb", requests.first())
            assertEquals("use sid=1", requests[1])
            assertEquals(5, requests.count { it.startsWith("servernotifyregister ") })
        }
    }
}
