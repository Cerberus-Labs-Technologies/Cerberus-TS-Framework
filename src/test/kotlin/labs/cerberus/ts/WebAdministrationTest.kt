package labs.cerberus.ts

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import labs.cerberus.ts.event.EventBus
import labs.cerberus.ts.query.*
import labs.cerberus.ts.service.*
import labs.cerberus.ts.teamspeak.TeamSpeakServer
import labs.cerberus.ts.web.WebAdministrationApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.*
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.*
import kotlin.test.*

class WebAdministrationTest {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper()
    private class Api(val client: HttpClient, val port: Int, val admin: String, val read: String) {
        fun request(method: String, path: String, body: String? = null, token: String? = admin): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/web$path")).timeout(Duration.ofSeconds(10))
            token?.let { builder.header("Authorization", "Bearer $it") }
            return client.send(builder.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
        }
    }
    private fun withApi(test: (Api, AdministrationFixture, TeamSpeakServer, WebAdministrationApi) -> Unit) {
        val fixture = AdministrationFixture()
        TeamSpeakServer(ConnectionConfig(password = "test", minimumCommandInterval = Duration.ZERO), EventBus()) { fixture }.use { server ->
            server.connect()
            val tokens = TokenService(directory.resolve("tokens.json"))
            RestService(tokens).use { rest ->
                WebAdministrationApi(server, rest).use { api ->
                    rest.start(InetSocketAddress("127.0.0.1", 0))
                    HttpClient.newHttpClient().use { client ->
                        test(Api(client, rest.port!!, tokens.generate(setOf("GET /api/web/*", "POST /api/web/*", "PUT /api/web/*", "DELETE /api/web/*")), tokens.generate(setOf("GET /api/web/*"))), fixture, server, api)
                    }
                }
            }
        }
    }
    @Test fun `channel HTTP operations validate placement redact passwords and reject stale updates`() = withApi { api, fixture, _, _ ->
        fun create(name: String, parent: Int = 0): Int {
            val response = api.request("POST", "/channels", mapper.writeValueAsString(mapOf("name" to name, "parentId" to parent, "newPassword" to "private-channel-secret")))
            assertEquals(200, response.statusCode(), response.body()); return mapper.readTree(response.body())["id"].asInt()
        }
        val id = create("Radio | Lounge")
        val detail = api.request("GET", "/channels/$id")
        assertEquals(200, detail.statusCode()); assertFalse(detail.body().contains("private-channel-secret")); assertFalse(detail.body().contains("channel_password"))
        val revision = mapper.readTree(detail.body())["revision"].asText()
        val edit = api.request("PUT", "/channels/$id", mapper.writeValueAsString(mapOf("name" to "Neu", "description" to "Zeile 1\nZeile 2 | /", "codecQuality" to 8, "maxClients" to -1, "lifetime" to "semi", "expectedRevision" to revision)))
        assertEquals(200, edit.statusCode(), edit.body())
        val command = fixture.commands.last { it.name == "channeledit" }
        assertEquals("1", command.parameters["channel_flag_semi_permanent"]); assertEquals("0", command.parameters["channel_flag_permanent"])
        assertTrue(command.wire().contains("Zeile\\s1\\nZeile\\s2\\s\\p\\s\\/"))
        assertEquals(409, api.request("PUT", "/channels/$id", """{"name":"Stale","expectedRevision":"$revision"}""").statusCode())
        assertEquals("Neu", mapper.readTree(api.request("GET", "/channels/$id").body())["name"].asText())
        for (body in listOf("""{"name":""}""", """{"name":"x","codecQuality":11}""", """{"name":"x","maxClients":-2}""", """{"name":"x","lifetime":"forever"}""", """{"name":"x","newPassword":"bad\u0000"}""")) assertEquals(400, api.request("PUT", "/channels/$id", body).statusCode(), body)
        val child = create("Unterchannel", id)
        assertEquals(409, api.request("DELETE", "/channels/$id", """{"expectedName":"Neu"}""").statusCode())
        assertEquals(400, api.request("POST", "/channels/$id/move", """{"parentId":$child}""").statusCode())
        assertEquals(400, api.request("POST", "/channels/$id/move", """{"parentId":0,"order":2}""").statusCode())
        assertEquals(400, api.request("POST", "/channels/$id/move", """{"parentId":$id}""").statusCode())
        assertEquals(204, api.request("POST", "/channels/$id/move", """{"parentId":2,"order":0}""").statusCode())
        assertEquals(2, mapper.readTree(api.request("GET", "/channels/$id").body())["parentId"].asInt())
        assertEquals(409, api.request("DELETE", "/channels/1", """{"expectedName":"Lobby"}""").statusCode())
        assertEquals(400, api.request("DELETE", "/channels/$child", """{"expectedName":"Unterchannel","force":true}""").statusCode())
        assertEquals(204, api.request("DELETE", "/channels/$child", """{"expectedName":"Unterchannel"}""").statusCode())
        assertEquals(409, api.request("DELETE", "/channels/$id", """{"expectedName":"alter Name"}""").statusCode())
        assertEquals(204, api.request("DELETE", "/channels/$id", """{"expectedName":"Neu"}""").statusCode())
        assertEquals(404, api.request("GET", "/channels/$id").statusCode())
        assertEquals(400, api.request("GET", "/channels/0").statusCode())
        assertFalse(fixture.commands.any { it.name == "channeldelete" && it.parameters["force"] == "1" })
    }
    @Test fun `group HTTP operations copy rights manage permissions memberships and reset channel assignments`() = withApi { api, fixture, _, _ ->
        assertEquals(200, api.request("GET", "/permission-catalog").statusCode())
        assertEquals(QueryScope.INSTANCE, fixture.commands.last { it.name == "permissionlist" }.scope)
        for ((kind, source) in listOf("server" to 6, "channel" to 5)) {
            val prefix = "/groups/$kind"
            val created = api.request("POST", prefix, """{"name":"Testgruppe","copyFrom":$source}""")
            assertEquals(200, created.statusCode(), created.body()); val id = mapper.readTree(created.body())["id"].asInt()
            val copy = fixture.commands.last { it.name.endsWith("groupcopy") }
            assertEquals("0", copy.parameters[if (kind == "server") "tsgid" else "tcgid"]); assertEquals("1", copy.parameters["type"])
            assertEquals(204, api.request("PUT", "$prefix/$id", """{"name":"Geändert","expectedName":"Testgruppe"}""").statusCode())
            assertEquals(409, api.request("PUT", "$prefix/$id", """{"name":"Veraltet","expectedName":"Testgruppe"}""").statusCode())
            val permissions = "/permissions/$kind-group/$id"
            assertEquals(204, api.request("PUT", "$permissions/100", if(kind=="server") """{"value":42,"negated":true,"skip":true}""" else """{"value":42}""").statusCode())
            val right = mapper.readTree(api.request("GET", permissions).body())[0]
            assertEquals(42, right["value"].asInt()); assertEquals(kind == "server", right["skip"].asBoolean())
            if (kind == "channel") assertEquals(400, api.request("PUT", "$permissions/100", """{"value":1,"skip":true}""").statusCode())
            assertEquals(403, api.request("PUT", "$permissions/9999", """{"value":1}""").statusCode())
            assertEquals(204, api.request("DELETE", "$permissions/100").statusCode()); assertEquals("[]", api.request("GET", permissions).body())
            assertEquals(204, api.request("POST", "$prefix/$id/members", """{"databaseId":99,"channelId":3}""").statusCode())
            assertEquals(99, mapper.readTree(api.request("GET", "$prefix/$id/members").body())[0]["databaseId"].asInt())
            assertEquals(409, api.request("DELETE", "$prefix/$id", """{"expectedName":"Geändert"}""").statusCode())
            val memberPath = "$prefix/$id/members/99" + if (kind == "channel") "/3" else ""
            assertEquals(204, api.request("DELETE", memberPath).statusCode())
            assertEquals("[]", api.request("GET", "$prefix/$id/members").body())
            if (kind == "channel") {
                val reset = fixture.commands.last { it.name == "setclientchannelgroup" }; assertEquals("6", reset.parameters["cgid"])
                assertEquals(409, api.request("DELETE", memberPath).statusCode())
            }
            assertEquals(204, api.request("POST", "$prefix/$id/members", """{"databaseId":99,"channelId":3}""").statusCode())
            assertEquals(204, api.request("DELETE", "$prefix/$id", """{"expectedName":"Geändert","force":true}""").statusCode())
            assertEquals(404, api.request("GET", "$prefix/$id/members").statusCode())
        }
        assertEquals(403, api.request("PUT", "/groups/server/8", """{"name":"Query umbenennen"}""").statusCode())
        assertEquals(403, api.request("PUT", "/permissions/server-group/8/100", """{"value":1}""").statusCode())
        assertEquals(400, api.request("POST", "/groups/server", """{"name":"Copy","copyFrom":8}""").statusCode())
        assertEquals(404, api.request("GET", "/groups/unknown").statusCode())
        assertEquals(204, api.request("PUT", "/permissions/channel/3/101", """{"value":15}""").statusCode())
        assertEquals(15, mapper.readTree(api.request("GET", "/permissions/channel/3").body())[0]["value"].asInt())
        assertEquals(204, api.request("DELETE", "/permissions/channel/3/101").statusCode())
    }
    @Test fun `bearer scopes reject every write before query execution and lifecycle removes routes`() = withApi { api, fixture, server, registration ->
        assertEquals(401, api.request("GET", "/groups/server", token = null).statusCode())
        assertEquals(200, api.request("GET", "/groups/server", token = api.read).statusCode())
        val before = fixture.commands.size
        for ((method, path, body) in listOf(
            Triple("POST", "/channels", """{"name":"Denied"}"""), Triple("PUT", "/channels/3", """{"name":"Denied"}"""),
            Triple("POST", "/channels/3/move", """{"parentId":0}"""), Triple("DELETE", "/channels/3", """{"expectedName":"Support"}"""),
            Triple("POST", "/groups/server", """{"name":"Denied"}"""), Triple("PUT", "/groups/server/7", """{"name":"Denied"}"""),
            Triple("DELETE", "/groups/server/7", """{"expectedName":"Mitglied"}"""), Triple("PUT", "/permissions/server-group/7/100", """{"value":1}"""),
            Triple("DELETE", "/permissions/server-group/7/100", null), Triple("POST", "/groups/server/7/members", """{"databaseId":99}"""),
            Triple("DELETE", "/groups/server/7/members/99", null), Triple("DELETE", "/groups/channel/5/members/99/3", null)
        )) assertEquals(401, api.request(method, path, body, api.read).statusCode(), "$method $path")
        assertEquals(before, fixture.commands.size)
        assertEquals(400, api.request("POST", "/groups/server", """{"name":"x","tsgid":7}""").statusCode())
        assertEquals(400, api.request("POST", "/groups/channel/5/members", """{"databaseId":null,"channelId":3}""").statusCode())
        server.close(); assertEquals(503, api.request("GET", "/groups/server").statusCode())
        registration.close(); assertEquals(404, api.request("GET", "/groups/server").statusCode())
    }
    @Test fun `competing administrative writes are rejected instead of queued`() = withApi { api, fixture, _, _ ->
        fixture.blocked = CountDownLatch(1); fixture.release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit<HttpResponse<String>> { api.request("POST", "/channels", """{"name":"Blocked"}""") }
            assertTrue(fixture.blocked.await(5, TimeUnit.SECONDS))
            assertEquals(409, api.request("POST", "/groups/server", """{"name":"Busy"}""").statusCode())
            fixture.release.countDown(); assertEquals(200, pending.get(5, TimeUnit.SECONDS).statusCode())
        } finally { fixture.release.countDown(); executor.shutdownNow() }
    }
}
