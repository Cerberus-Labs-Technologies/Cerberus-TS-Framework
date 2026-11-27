package labs.cerberus.ts

import labs.cerberus.ts.command.*
import labs.cerberus.ts.command.Commands.commandSpec
import labs.cerberus.ts.command.Commands.paramSpec
import labs.cerberus.ts.command.Commands.argumentSpec
import labs.cerberus.ts.event.*
import labs.cerberus.ts.query.*
import labs.cerberus.ts.service.*
import labs.cerberus.ts.teamspeak.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class CommandParityTest {
    @TempDir lateinit var directory: Path
    private class Query : QueryConnection {
        override var isOpen = true
        override val supportsNotifications = true
        var sender = "alice-uid"
        val messages = mutableListOf<String>()
        val history = mutableListOf<QueryCommand>()
        private val users = mapOf(10 to ("alice-uid" to "Alice"), 20 to ("bob-uid" to "Bob"))
        override fun execute(command: QueryCommand): QueryResponse {
            history += command
            val rows = when (command.name) {
                "version" -> listOf(mapOf("version" to "6.0.0-beta14"))
                "whoami" -> listOf(mapOf("client_id" to "99", "virtualserver_id" to "1"))
                "clientinfo" -> listOf(mapOf("client_unique_identifier" to sender, "client_nickname" to "Alice", "client_servergroups" to "6"))
                "clientlist" -> listOf(
                    mapOf("clid" to "1", "client_database_id" to "10", "client_unique_identifier" to "alice-uid", "client_nickname" to "Alice"),
                    mapOf("clid" to "2", "client_database_id" to "30", "client_unique_identifier" to "sam-a", "client_nickname" to "Sam"),
                    mapOf("clid" to "3", "client_database_id" to "31", "client_unique_identifier" to "sam-b", "client_nickname" to "Sam Two"),
                    mapOf("clid" to "4", "client_unique_identifier" to "temporary", "client_nickname" to "No DB"),
                    mapOf("clid" to "99", "client_type" to "1", "client_nickname" to "Query"))
                "channellist" -> listOf(mapOf("cid" to "1", "channel_name" to "Lobby"), mapOf("cid" to "2", "channel_name" to "Music"), mapOf("cid" to "3", "channel_name" to "Music Talk"))
                "clientdbfind" -> {
                    val pattern = command.parameters.getValue("pattern").trim('%')
                    users.filterValues { (uid, name) -> if ("uid" in command.options) uid == pattern else name.contains(pattern, true) }.keys.map { mapOf("cldbid" to "$it") }
                }
                "clientdbinfo" -> users[command.parameters.getValue("cldbid").toInt()]?.let { (uid, name) -> listOf(mapOf("client_unique_identifier" to uid, "client_nickname" to name)) }.orEmpty()
                "sendtextmessage" -> { messages += command.parameters.getValue("msg"); emptyList() }
                else -> emptyList()
            }
            return QueryResponse(rows, QueryError(0, "ok"))
        }
        override fun onNotification(listener: (QueryNotification) -> Unit) = AutoCloseable {}
        override fun onDisconnect(listener: (Throwable?) -> Unit) = AutoCloseable {}
        override fun close() { isOpen = false }
    }
    private fun fixture(action: (CommandService, PermissionService, Query, LocalizationService) -> Unit) {
        val query = Query()
        TeamSpeakServer(ConnectionConfig(password = "fixture"), EventBus()) { query }.use { server ->
            server.connect()
            val permissions = PermissionService(directory.resolve("permissions.json"))
            val locales = LocalizationService(directory = directory.resolve("locales"))
            action(CommandService(server, permissions, localization = locales), permissions, query, locales)
        }
    }
    private fun invoke(commands: CommandService, input: String): List<String> = mutableListOf<String>().also { commands.console(input, it::add) }
    private fun chat(commands: CommandService, input: String) = commands.handle(TextMessage(1, "alice-uid", "Alice", 1, "!$input"))

    @Test fun `old positional optional and first matching scenarios bind one token without leaking abandoned branches`() = fixture { commands, _, _, _ ->
        val seen = mutableListOf<Map<String, Any>>()
        commands.registerCommand(commandSpec("test", "fixture:test").parameters(
            paramSpec().optional(paramSpec("optional", String::class.java)), paramSpec("number", Int::class.javaObjectType)
        ).executor { seen += it.values }.build())
        assertTrue(invoke(commands, "fixture:test 7").isEmpty()); assertEquals(mapOf("number" to 7), seen.last())
        invoke(commands, "test label 8"); assertEquals(mapOf("optional" to "label", "number" to 8), seen.last())
        commands.registerCommand(commandSpec("first-of").parameters(paramSpec().firstMatching(
            paramSpec("integer", Int::class.javaObjectType), paramSpec("text", String::class.java)), paramSpec("tail", Int::class.javaObjectType))
            .executor { seen += it.values }.build())
        invoke(commands, "first-of hello 9"); assertEquals(mapOf("text" to "hello", "tail" to 9), seen.last())
        invoke(commands, "first-of 4 9"); assertEquals(mapOf("integer" to 4, "tail" to 9), seen.last())
        val before = seen.size
        assertTrue(invoke(commands, "test invalid").single().contains("number")); assertEquals(before, seen.size)
    }

    @Test fun `old named options accept aliases flags separated values and explicit values while rejecting ambiguity and duplicates`() = fixture { commands, _, _, _ ->
        val seen = mutableListOf<TypedCommandContext>()
        commands.registerCommand(commandSpec("argumentized").arguments(argumentSpec("name", "n", String::class.java),
            argumentSpec("count", "c", Int::class.javaObjectType), argumentSpec().optional(argumentSpec("enabled", "e", Boolean::class.javaObjectType)))
            .executor { seen += it }.build())
        invoke(commands, "argumentized -n \"two words\" --count=-12 --enabled")
        assertEquals("two words", seen.last().required<String>("name")); assertEquals(-12, seen.last().required<Int>("count")); assertTrue(seen.last().required("enabled"))
        invoke(commands, "argumentized --name=other -c 7 --enabled false"); assertFalse(seen.last().required<Boolean>("enabled"))
        invoke(commands, "argumentized --name=other -c 7"); assertFalse(seen.last().hasOne("enabled"))
        for (input in listOf("-n=a --name=b -c=1", "-n=a -c=oops", "-n=a -c=1 --unknown=x", "-n=a -c=1 stray", "-n=a -c=1 -e=maybe")) {
            val count = seen.size; assertTrue(invoke(commands, "argumentized $input").isNotEmpty()); assertEquals(count, seen.size)
        }
        commands.registerCommand(commandSpec("clashed2").arguments(argumentSpec("name", "n", String::class.java), argumentSpec("neighbor", "n", String::class.java))
            .executor { it.reply(it.required<String>("name") + it.required<String>("neighbor")) }.build())
        assertEquals(listOf("ab"), invoke(commands, "clashed2 --name=a --neighbor=b"))
        assertTrue(invoke(commands, "clashed2 -n=a").single().contains("Ambiguous shorthand"))
    }

    @Test fun `quoted and escaped option-like tokens stay values and literal terminator preserves raw Paper arguments`() = fixture { commands, _, _, _ ->
        commands.registerCommand(commandSpec("echo").parameters(paramSpec("value", String::class.java)).executor { it.reply(it.required("value")) }.build())
        assertEquals(listOf("--literal"), invoke(commands, "echo \"--literal\""))
        assertEquals(listOf("--literal"), invoke(commands, "echo \\--literal"))
        assertEquals(listOf("--literal"), invoke(commands, "echo -- --literal"))
        assertTrue(invoke(commands, "echo --literal").single().contains("positional"))
        val parsed = CommandParser.parse("ns:command \"--literal\" --name=\"two words\" -- -tail")
        assertEquals("ns:command", parsed.name); assertEquals(listOf("--literal", "-tail"), parsed.arguments)
        assertEquals("two words", parsed.options["name"]); assertEquals(listOf("--literal", "--name=two words", "--", "-tail"), parsed.rawArguments)
        assertFails { CommandParser.parse("a:b:c") }
    }

    data class Custom(val name: String)
    @Test fun `custom matchers support notices replacement restoration and unloading without stale registrations`() = fixture { commands, _, _, _ ->
        val first = commands.matchers.register(Custom::class.java) { _, value -> MatchResult.Success(Custom("first:$value"), notices = listOf("matched")) }
        val spec = commandSpec("custom").parameters(paramSpec().optional(paramSpec("value", Custom::class.java)))
            .executor { it.reply(it.get<Custom>("value")?.name ?: "absent") }.build()
        commands.registerCommand(spec)
        assertEquals(listOf("absent"), invoke(commands, "custom")); assertEquals(listOf("first:x", "matched"), invoke(commands, "custom x"))
        val newer = commands.matchers.register(Custom::class.java) { _, value -> MatchResult.Success(Custom("new:$value")) }
        first.close(); assertEquals(listOf("new:x"), invoke(commands, "custom x"))
        newer.close(); assertTrue(invoke(commands, "custom x").single().contains("No matcher"))
        val override = commands.matchers.register(String::class.java) { _, input -> MatchResult.Success(input.uppercase()) }
        commands.registerCommand(commandSpec("string").parameters(paramSpec("value", String::class.java)).executor { it.reply(it.required("value")) }.build())
        assertEquals(listOf("ABC"), invoke(commands, "string abc")); override.close(); override.close()
        assertEquals(listOf("abc"), invoke(commands, "string abc"))
    }

    @Test fun `scalar numeric locale and primitive matchers reject overflow nonfinite values and missing optional criteria precisely`() = fixture { commands, _, _, _ ->
        commands.registerCommand(commandSpec("numbers").parameters(paramSpec("large", BigInteger::class.java), paramSpec("decimal", BigDecimal::class.java),
            paramSpec("flag", Boolean::class.javaPrimitiveType!!), paramSpec("language", Locale::class.java)).executor {
            assertEquals(BigInteger("999999999999999999999999999"), it.required("large"))
            assertEquals(BigDecimal("1.234567890123456789"), it.required("decimal")); assertTrue(it.required<Boolean>("flag"))
            assertEquals("de-DE", it.required<Locale>("language").toLanguageTag()); it.reply("ok")
        }.build())
        assertEquals(listOf("ok"), invoke(commands, "numbers 999999999999999999999999999 1.234567890123456789 y de_DE"))
        var count = 0
        commands.registerCommand(commandSpec("integer").parameters(paramSpec("value", Int::class.javaPrimitiveType!!)).executor { count++ }.build())
        commands.registerCommand(commandSpec("double").parameters(paramSpec("value", Double::class.java)).executor { count++ }.build())
        for (input in listOf("integer 999999999999", "double NaN", "double Infinity", "double -Infinity")) assertTrue(invoke(commands, input).isNotEmpty())
        assertEquals(0, count)
        assertFails { commands.registerCommand(commandSpec("unknown").parameters(paramSpec("x", Number::class.java)).executor {}.build()) }
    }

    @Test fun `clients channels offline users framework groups and subjects resolve with explicit ids and ambiguity errors`() = fixture { commands, permissions, _, _ ->
        val seen = mutableListOf<TypedCommandContext>()
        fun register(name: String, type: Class<*>) { commands.registerCommand(commandSpec(name).parameters(paramSpec("target", type)).executor { seen += it }.build()) }
        register("client", Client::class.java); register("channel", Channel::class.java); register("user", TeamSpeakUser::class.java)
        register("group", PermissionGroup::class.java); register("subject", PermissionSubject::class.java)
        invoke(commands, "client c:1"); assertEquals(1, seen.last().required<Client>("target").id); assertEquals(1, seen.last().required<Int>("targetId"))
        invoke(commands, "client 10"); assertEquals("alice-uid", seen.last().required<Client>("target").uniqueId)
        var before = seen.size; assertTrue(invoke(commands, "client Sam").single().contains("Ambiguous")); assertEquals(before, seen.size)
        assertTrue(invoke(commands, "client 999999999999999999999").isNotEmpty()); assertEquals(before, seen.size)
        invoke(commands, "channel 2"); assertEquals(2, seen.last().required<Int>("targetId"))
        before = seen.size; assertTrue(invoke(commands, "channel Music").isNotEmpty()); assertEquals(before, seen.size)
        invoke(commands, "user db:20"); assertEquals("Bob", seen.last().required<TeamSpeakUser>("target").nickname); assertEquals(20, seen.last().required<Int>("targetId"))
        invoke(commands, "user uid:bob-uid"); assertEquals(20, seen.last().required<TeamSpeakUser>("target").databaseId)
        invoke(commands, "user c:1"); assertEquals(10, seen.last().required<TeamSpeakUser>("target").databaseId)
        invoke(commands, "user Bob"); assertEquals("bob-uid", seen.last().required<TeamSpeakUser>("target").uniqueId)
        permissions.createGroup("moderators"); invoke(commands, "group moderators"); assertEquals("moderators", seen.last().required<PermissionGroup>("target").id)
        permissions.grantUser("alice-uid", "example.use", 1); invoke(commands, "subject alice-uid"); assertEquals("alice-uid", seen.last().required<PermissionSubject>("target").id)
    }

    @Test fun `minimum permissions run before matching and are rechecked after matching including recycled client ids`() = fixture { commands, permissions, query, locales ->
        val matches = AtomicInteger(); var executed = 0
        val matcher = commands.matchers.register(Custom::class.java) { _, value -> matches.incrementAndGet(); MatchResult.Success(Custom(value)) }
        val spec = commandSpec("protected").permission("test:subcommand", 4).parameters(paramSpec("value", Custom::class.java)).executor { executed++ }.build()
        commands.registerCommand(spec)
        permissions.grantUser("alice-uid", "test:subcommand", 3); chat(commands, "protected x")
        assertEquals(0, matches.get()); assertEquals(0, executed); assertEquals("Permission denied.", query.messages.last())
        permissions.grantUser("alice-uid", "test:subcommand", 4); chat(commands, "protected x"); assertEquals(1, executed)
        matcher.close()
        commands.matchers.register(Custom::class.java) { _, value -> permissions.removeUserPermission("alice-uid", "test:subcommand"); MatchResult.Success(Custom(value)) }
        chat(commands, "protected x"); assertEquals(1, executed); assertTrue(query.messages.last().contains("changed"))
        permissions.grantUser("alice-uid", "test:subcommand", 4)
        commands.matchers.register(Custom::class.java) { _, value -> query.sender = "recycled"; MatchResult.Success(Custom(value)) }
        query.messages.clear(); chat(commands, "protected x"); assertEquals(1, executed); assertTrue(query.messages.isEmpty())
        query.sender = "alice-uid"; locales.setLocaleForClient("alice-uid", Locale.GERMAN)
        commands.registerCommand(commandSpec("localized").parameters(paramSpec("number", Int::class.java)).executor {}.build())
        chat(commands, "localized invalid"); assertTrue(query.messages.last().contains("gültiger"))
    }

    @Test fun `schema validation alias collision replacement help and stale handles keep registrations atomic`() = fixture { commands, _, _, _ ->
        commands.installBuiltins()
        fun spec(name: String, alias: String, text: String) = commandSpec(name, alias).description("description").executor { it.reply(text) }.build()
        val one = spec("one", "shared", "one"); val handle = commands.registerCommand(one)
        assertFails { commands.registerCommand(spec("two", "shared", "two")) }; assertEquals(listOf("one"), invoke(commands, "shared"))
        assertTrue(invoke(commands, "two").single().startsWith("Unknown"))
        assertFails { commands.registerCommand(commandSpec("clashed").arguments(argumentSpec("same", "a", String::class.java), argumentSpec("same", "b", Int::class.java)).executor {}.build()) }
        assertTrue(invoke(commands, "help shared").single().contains("description"))
        val replacement = spec("two", "shared", "two"); val newer = commands.registerCommand(replacement, replace = true)
        handle.close(); assertEquals(listOf("two"), invoke(commands, "shared")); assertNull(commands.getCommand("one"))
        assertSame(replacement, commands.getCommand("shared")); assertEquals(listOf(replacement), commands.getCommands())
        commands.unregisterCommand(one); assertEquals(listOf("two"), invoke(commands, "shared"))
        newer.close(); newer.close(); assertTrue(commands.getCommands().isEmpty())
    }

    @Test fun `ambiguous optional graphs stop at a bounded number of matching attempts`() = fixture { commands, _, _, _ ->
        val parameters = (1..20).map { paramSpec().optional(paramSpec("option$it", String::class.java)) } + paramSpec("number", Int::class.java)
        var executed = false
        commands.registerCommand(commandSpec("bounded").parameters(*parameters.toTypedArray()).executor { executed = true }.build())
        val replies = invoke(commands, "bounded " + (1..10).joinToString(" ") { "word" })
        assertTrue(replies.single().contains("limit")); assertFalse(executed)
    }

    @Test fun `unloading during a blocked matcher prevents the executor from starting`() = fixture { commands, _, _, _ ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val executed = AtomicInteger()
        commands.matchers.register(Custom::class.java) { _, value -> entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); MatchResult.Success(Custom(value)) }
        val registration = commands.registerCommand(commandSpec("slow").parameters(paramSpec("value", Custom::class.java)).executor { executed.incrementAndGet() }.build())
        Executors.newSingleThreadExecutor().use { worker ->
            val invocation = worker.submit<List<String>> { invoke(commands, "slow x") }
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS)); registration.close(); release.countDown()
                assertEquals(listOf("Command failed."), invocation.get(3, TimeUnit.SECONDS)); assertEquals(0, executed.get())
            } finally { release.countDown() }
        }
    }
}
