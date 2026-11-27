package labs.cerberus.ts

import com.zaxxer.hikari.HikariDataSource
import jakarta.persistence.*
import labs.cerberus.ts.event.*
import labs.cerberus.ts.rest.*
import labs.cerberus.ts.service.*
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.lang.reflect.Proxy
import java.lang.reflect.InvocationTargetException
import javax.sql.DataSource
import java.util.UUID
import java.util.concurrent.*
import kotlin.test.*

@Entity(name = "ParityNote") @Table(name = "parity_notes")
open class ParityNote(@field:Id open var id: Int = 0, open var label: String = "")

class DatabaseParityTest {
    @TempDir lateinit var directory: Path
    private fun url() = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
    private fun config(id: String = "default", url: String = url()) = PersistenceUnitConfig(id, url,
        pool = mapOf("maximumPoolSize" to "3"), hibernate = mapOf("hibernate.hbm2ddl.auto" to "create-drop"))
    private fun count(connection: Connection, table: String = "parity_notes") = connection.createStatement().use { statement ->
        statement.executeQuery("select count(*) from $table").use { it.next(); it.getInt(1) }
    }

    @Test fun `nested jdbc shares one transaction and caught inner failures roll back all changes`() {
        DatabaseService().use { db ->
            val source = JdbcDataSource().apply { setURL(url()) }
            db.register("default", source).use {
                db.transaction { it.createStatement().use { sql -> sql.execute("create table records(id int primary key)") } }
                db.transaction { outer ->
                    outer.createStatement().use { it.execute("insert into records values(1)") }
                    db.transaction { inner -> assertSame(outer, inner); inner.createStatement().use { it.execute("insert into records values(2)") } }
                    assertEquals(2, count(outer, "records"))
                }
                assertFailsWith<RollbackOnlyException> {
                    db.transaction { outer ->
                        outer.createStatement().use { it.execute("insert into records values(3)") }
                        assertFailsWith<IllegalArgumentException> { db.transaction<Unit> { throw IllegalArgumentException("inner") } }
                    }
                }
                assertEquals(2, db.transaction { count(it, "records") })
                // Closing from inside the transaction is rejected without destroying the registry.
                db.transaction { assertFailsWith<IllegalStateException> { db.close() } }
            }
            assertFails { db.transaction {} }
            source.connection.use { assertEquals(2, count(it, "records")) } // external DataSource still belongs to caller
        }
    }

    @Test fun `nested recovery callbacks run after rollback and may close their registration`() {
        DatabaseService().use { db ->
            val source = JdbcDataSource().apply { setURL(url()) }
            val registration = db.register("default", source)
            db.transaction { it.createStatement().use { sql -> sql.execute("create table records(id int)") } }
            lateinit var connection: Connection
            val calls = mutableListOf<String>()
            assertFailsWith<RollbackOnlyException> {
                db.transaction { outer ->
                    connection = outer
                    assertNull(db.transaction<Unit>("default", {
                        it.createStatement().use { sql -> sql.execute("insert into records values(1)") }; error("rollback")
                    }, {
                        assertTrue(connection.isClosed)
                        source.connection.use { assertEquals(0, count(it, "records")) }
                        registration.close(); calls += "callback"
                    }))
                    calls += "outer"
                }
            }
            assertEquals(listOf("outer", "callback"), calls)
            registration.close()
        }
    }

    @Test fun `a partially failed transaction begin rolls back before restoring autocommit`() {
        val actual = JdbcDataSource().apply { setURL(url()) }
        actual.connection.use { connection -> connection.createStatement().use { it.execute("create table records(id int)") } }
        lateinit var leased: Connection
        val failure = SQLException("begin failed after changing connection state")
        val source = object : DataSource by actual {
            override fun getConnection(): Connection {
                leased = actual.connection
                return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                    if (method.name == "setAutoCommit" && args?.singleOrNull() == false) {
                        leased.autoCommit = false
                        leased.createStatement().use { it.execute("insert into records values(1)") }
                        throw failure
                    }
                    try { method.invoke(leased, *(args ?: emptyArray())) } catch (error: InvocationTargetException) { throw error.targetException }
                } as Connection
            }
        }
        DatabaseService().use { db ->
            db.register("default", source)
            assertSame(failure, assertFailsWith<SQLException> { db.transaction { fail("Action must not run") } })
            assertTrue(leased.isClosed)
            actual.connection.use { assertEquals(0, count(it, "records")) }
        }
    }

    @Test fun `orm persists entities shares jdbc and nested entity managers and cleans up manual sessions`() {
        DatabaseService().use { db ->
            val unit = db.createUnit(config(), listOf(ParityNote::class.java))
            lateinit var first: EntityManager
            unit.withEntityManager { outer ->
                first = outer; outer.persist(ParityNote(1, "one"))
                unit.withEntityManager { inner -> assertSame(outer, inner); inner.persist(ParityNote(2, "two")) }
                assertEquals(2, db.transaction { count(it) }) // flush and same physical transaction
            }
            assertFalse(first.isOpen)
            unit.withEntityManager { next -> assertNotSame(first, next); assertEquals("one", next.find(ParityNote::class.java, 1).label) }
            assertFailsWith<RollbackOnlyException> {
                unit.withEntityManager { manager ->
                    manager.persist(ParityNote(3, "three"))
                    assertFails { db.transaction<Unit> { it.createStatement().use { sql -> sql.execute("insert into parity_notes values(4, 'four')") }; error("abort") } }
                }
            }
            assertEquals(2, db.transaction { count(it) })
            val manual = unit.entityManager
            manual.transaction.begin(); manual.persist(ParityNote(5, "uncommitted"))
            assertTrue(manual.isOpen)
            unit.withEntityManager { assertFailsWith<IllegalStateException> { unit.close() } }
            unit.close(); unit.close()
            assertFalse(manual.isOpen); assertTrue((unit.dataSource as HikariDataSource).isClosed)
            assertFails { unit.entityManager }; assertFails { unit.withEntityManager {} }
            assertTrue(db.removeUnit("default")); assertFalse(db.removeUnit("default"))
            val replacement = db.createUnit(config(), listOf(ParityNote::class.java))
            replacement.withEntityManager { it.persist(ParityNote(1, "replacement")) }
            assertEquals(1, db.transaction { count(it) })
        }
    }

    @Test fun `units and threads have isolated transactions and shutdown waits for work already running`() {
        val db = DatabaseService()
        val one = db.createUnit(config("one"), listOf(ParityNote::class.java))
        val two = db.createUnit(config("two"), listOf(ParityNote::class.java))
        one.withEntityManager { first -> two.withEntityManager { second -> assertNotSame(first, second); second.persist(ParityNote(1, "two")) } }
        assertEquals(0, db.transaction("one") { count(it) }); assertEquals(1, db.transaction("two") { count(it) })
        Executors.newFixedThreadPool(3).use { executor ->
            val entered = CountDownLatch(2); val release = CountDownLatch(1)
            val managers = ConcurrentHashMap.newKeySet<EntityManager>()
            val work = (1..2).map { index -> executor.submit {
                one.withEntityManager { manager ->
                    managers += manager; manager.persist(ParityNote(index, "thread")); entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            } }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); assertEquals(2, managers.size)
            val shutdown = executor.submit { db.close() }
            assertFailsWith<TimeoutException> { shutdown.get(50, TimeUnit.MILLISECONDS) }
            release.countDown(); work.forEach { it.get(5, TimeUnit.SECONDS) }; shutdown.get(5, TimeUnit.SECONDS)
            assertTrue((one.dataSource as HikariDataSource).isClosed); assertTrue((two.dataSource as HikariDataSource).isClosed)
        }
        db.close()
    }

    @Test fun `json and yaml units load atomically including legacy connection and pool keys`() {
        val legacy = directory.resolve("legacy.json")
        Files.writeString(legacy, """{"url":"${url()}","user":"sa","pass":"example","dataSourceOpts":{"maximumPoolSize":"2"},"hibernate":{"hibernate.hbm2ddl.auto":"create-drop"}}""")
        val parsed = PersistenceUnitConfig.read(legacy)
        assertEquals("example", parsed.password); assertEquals("2", parsed.pool["maximumPoolSize"])
        assertFalse(parsed.toString().contains("example"))
        Files.writeString(directory.resolve("second.yml"), """
            jdbcUrl: '${url()}'
            pool:
              maximumPoolSize: 2
            hibernate:
              hibernate.hbm2ddl.auto: create-drop
        """.trimIndent())
        DatabaseService().use { db ->
            assertEquals(setOf("legacy", "second"), db.load(directory, listOf(ParityNote::class.java)).map { it.id }.toSet())
            db.requireUnit("legacy").withEntityManager { it.persist(ParityNote(1, "legacy")) }
            assertEquals(1, db.transaction("legacy") { count(it) })
            assertEquals(0, db.transaction("second") { count(it) })
            assertFails { db.load(directory) }
        }
        Files.writeString(directory.resolve("z-invalid.yml"), "jdbcUrl: '${url()}'\nentities: [does.not.Exist]\n")
        DatabaseService().use { db ->
            assertFails { db.load(directory, listOf(ParityNote::class.java)) }; assertTrue(db.unitIds().isEmpty())
        }
        Files.writeString(directory.resolve("duplicate.yml"), "jdbcUrl: '${url()}'\njdbcUrl: 'jdbc:bad:'\n")
        assertFails { PersistenceUnitConfig.read(directory.resolve("duplicate.yml")) }
    }

    class Changed(val id: Int, val fail: Boolean)
    class TransactionalListener(private val db: DatabaseService) {
        @Subscribe @Transactional fun changed(event: Changed) {
            db.withEntityManager { it.persist(ParityNote(event.id, "event")) }
            if (event.fail) error("listener failed")
        }
    }
    @RestController("/database")
    class TransactionalController(private val db: DatabaseService) {
        @Transactional @RequestMapping(RequestMethod.POST, "/:id")
        fun insert(@PathParam("id") id: Int, @RequestParam("fail", defaultValue = "false") fail: Boolean): Map<String, Int> {
            db.withEntityManager { it.persist(ParityNote(id, "http")) }
            if (fail) throw RestException(409, "Requested rollback")
            return mapOf("id" to id)
        }
    }
    @Test fun `transactional event and http invocations commit successes and roll back original errors`() {
        DatabaseService().use { db ->
            db.createUnit(config(), listOf(ParityNote::class.java))
            val errors = mutableListOf<Throwable>()
            EventBus(db) { errors += it }.apply {
                register(TransactionalListener(db)); publish(Changed(1, false)); publish(Changed(2, true))
            }
            assertEquals(1, errors.size); assertEquals("listener failed", errors.single().message)
            assertEquals(1, db.transaction { count(it) })
            assertFails { EventBus().register(TransactionalListener(db)) }
            val tokens = TokenService(directory.resolve("tokens.json")); val token = tokens.generate(setOf("POST /database/*"))
            RestService(tokens, db).use { rest ->
                rest.registerController(TransactionalController(db)); rest.start(InetSocketAddress("127.0.0.1", 0))
                HttpClient.newHttpClient().use { http ->
                    fun post(path: String) = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${rest.port}/database/$path"))
                        .header("Authorization", "Bearer $token").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
                    assertEquals(200, post("3").statusCode()); val rejected = post("4?fail=true")
                    assertEquals(409, rejected.statusCode()); assertTrue(rejected.body().contains("Requested rollback"))
                    assertEquals(400, post("invalid").statusCode())
                }
            }
            assertEquals(2, db.transaction { count(it) })
        }
    }
}
