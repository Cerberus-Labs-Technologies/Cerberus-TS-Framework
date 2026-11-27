package labs.cerberus.ts

import labs.cerberus.ts.service.DatabaseService
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Test
import kotlin.test.*

class DatabaseServiceTest {
    @Test fun `JDBC transactions commit successes roll back failures and release connections`() {
        val source = JdbcDataSource().apply { setURL("jdbc:h2:mem:cerberus;DB_CLOSE_DELAY=-1") }
        val database = DatabaseService()
        val registration = database.register("plugin", source)
        database.transaction("plugin") { it.createStatement().use { sql -> sql.execute("CREATE TABLE example(id INT PRIMARY KEY)") } }
        database.transaction("plugin") { it.prepareStatement("INSERT INTO example VALUES (?)").use { sql -> sql.setInt(1, 1); sql.executeUpdate() } }
        assertFails {
            database.transaction("plugin") { connection ->
                connection.createStatement().use { it.executeUpdate("INSERT INTO example VALUES (2)") }
                error("rollback")
            }
        }
        val count = database.transaction("plugin") { connection ->
            connection.createStatement().use { sql -> sql.executeQuery("SELECT COUNT(*) FROM example").use { it.next(); it.getInt(1) } }
        }
        assertEquals(1, count)
        registration.close()
        assertFails { database.transaction("plugin") { } }
    }
}
