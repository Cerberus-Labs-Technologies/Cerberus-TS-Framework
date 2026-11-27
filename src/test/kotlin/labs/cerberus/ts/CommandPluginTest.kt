package labs.cerberus.ts

import labs.cerberus.ts.command.*
import labs.cerberus.ts.event.EventBus
import labs.cerberus.ts.plugin.*
import labs.cerberus.ts.query.ConnectionConfig
import labs.cerberus.ts.service.*
import labs.cerberus.ts.teamspeak.TeamSpeakServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.*

class CommandPluginTest {
    @TempDir lateinit var directory: Path
    @Test fun `actual Java Paper-style plugin registers custom typed command and scopes both command and matcher to its lifecycle`() {
        val source = Files.createDirectories(directory.resolve("source/fixture")).resolve("CommandsPlugin.java")
        Files.writeString(source, """
            package fixture;
            import labs.cerberus.ts.command.*;
            import labs.cerberus.ts.plugin.JavaPlugin;
            import static labs.cerberus.ts.command.Commands.*;
            public class CommandsPlugin extends JavaPlugin {
                public static class Tag { final String text; public Tag(String text) { this.text=text; } }
                public static class TagMatcher implements CriterionMatcher<Tag> {
                    public Class<Tag> getSupportedType() { return Tag.class; }
                    public MatchResult<Tag> tryMatch(MatchingContext ctx, String input) {
                        return new MatchResult.Success<>(new Tag(input), java.util.Map.of(), java.util.List.of());
                    }
                }
                @Override public void onLoad() {
                    registerMatcher(new TagMatcher());
                    registerCommand(commandSpec("java-typed", "fixture:typed")
                        .parameters(paramSpec("tag", Tag.class))
                        .permission("example.command", 4)
                        .executor(ctx -> ctx.reply("typed:" + ctx.getRequiredOne("tag", Tag.class).text))
                        .build());
                }
            }
        """.trimIndent())
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
        val classes = Files.createDirectories(directory.resolve("classes"))
        val classpath = listOf(JavaPlugin::class.java, Function1::class.java).map {
            Path.of(it.protectionDomain.codeSource.location.toURI()).toString()
        }.distinct().joinToString(java.io.File.pathSeparator)
        val diagnostics = java.io.StringWriter()
        compiler.getStandardFileManager(null, null, null).use { files ->
            assertTrue(compiler.getTask(diagnostics, files, null, listOf("-proc:none", "-classpath", classpath, "-d", classes.toString()),
                null, files.getJavaFileObjectsFromPaths(listOf(source))).call(), diagnostics.toString())
        }
        val jars = Files.createDirectories(directory.resolve("jars"))
        JarOutputStream(Files.newOutputStream(jars.resolve("commands.jar"))).use { jar ->
            jar.putNextEntry(JarEntry("plugin.yml")); jar.write("name: CommandsFixture\nversion: '1'\nmain: fixture.CommandsPlugin\n".toByteArray()); jar.closeEntry()
            Files.walk(classes).use { paths -> paths.filter(Files::isRegularFile).forEach { file ->
                jar.putNextEntry(JarEntry(classes.relativize(file).toString().replace('\\', '/'))); Files.copy(file, jar); jar.closeEntry()
            } }
        }
        TeamSpeakServer(ConnectionConfig(password = "fixture"), EventBus()).use { server ->
            val commands = CommandService(server, PermissionService(directory.resolve("permissions.json")))
            val services = Services().apply { register(CommandService::class.java, commands); register(EventBus::class.java, EventBus()) }
            PluginManager(services, directory.resolve("data")).use { manager ->
                manager.load(jars)
                fun invoke(input: String) = mutableListOf<String>().also { commands.console(input, it::add) }
                assertEquals(listOf("Plugin is not enabled."), invoke("fixture:typed value"))
                manager.enable(); assertEquals(listOf("typed:value"), invoke("fixture:typed value"))
                val type = Class.forName("fixture.CommandsPlugin\$Tag", false, manager.getPlugin("commandsfixture")!!.javaClass.classLoader)
                assertTrue(commands.matchers.snapshot(setOf(type)).isNotEmpty())
                manager.close()
                assertTrue(invoke("java-typed value").single().startsWith("Unknown command"))
                assertTrue(invoke("fixture:typed value").single().startsWith("Unknown command"))
                assertFails { commands.matchers.snapshot(setOf(type)) }
            }
        }
    }
}
