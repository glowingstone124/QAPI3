package org.qo.services.llmServices

import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.R2dbcBadGrammarException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito
import org.qo.datas.ReactiveDatabase
import org.springframework.r2dbc.BadSqlGrammarException
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LLMAccessRecordSchemaTest {
    private lateinit var database: ReactiveDatabase

    @BeforeEach fun setUp() {
        val connectionFactory = ConnectionFactories.get(
            "r2dbc:h2:mem:///access_records_${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        )
        database = Mockito.spy(ReactiveDatabase(
            DatabaseClient.create(connectionFactory),
            TransactionalOperator.create(R2dbcTransactionManager(connectionFactory)),
        ))
    }

    private fun executedSql(): List<String> = Mockito.mockingDetails(database).invocations
        .filter { it.method.name == "execute" }.map { it.getArgument<String>(0) }

    @Test fun `fresh and already migrated schemas do not attempt to add existing columns`() = runTest {
        LLMAccessRecordSchema(database).ensure()
        // Simulate an application restart, rather than relying on its in-memory ready flag.
        LLMAccessRecordSchema(database).ensure()
        assertEquals(0, executedSql().count { it.startsWith("ALTER TABLE") })
        insertRecord("new", "web")
        assertEquals("web", database.one("SELECT source FROM llm_access_records WHERE username = 'new'") { it.get("source", String::class.java) })
    }

    @Test fun `legacy schema gains all missing columns and retains existing records`() = runTest {
        database.execute("""
            CREATE TABLE llm_access_records (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                uid BIGINT NOT NULL,
                username VARCHAR(128) NOT NULL,
                request_id VARCHAR(80) NOT NULL,
                model VARCHAR(128) NOT NULL,
                stream BOOLEAN NOT NULL,
                status VARCHAR(32) NOT NULL,
                prompt_tokens INT NULL,
                completion_tokens INT NULL,
                total_tokens INT NULL,
                error_message VARCHAR(512) NULL,
                created_at BIGINT NOT NULL,
                completed_at BIGINT NULL
            )
        """.trimIndent())
        database.execute("""
            INSERT INTO llm_access_records(uid, username, request_id, model, stream, status, created_at)
            VALUES (1, 'old', 'old-request', 'old-model', FALSE, 'completed', 1000)
        """.trimIndent())
        LLMAccessRecordSchema(database).ensure()
        assertEquals("unknown", database.one("SELECT source FROM llm_access_records WHERE username = 'old'") { it.get("source", String::class.java) })
        insertRecord("new", "qq")
        val usage = database.one("SELECT cached_tokens, uncached_tokens FROM llm_access_records WHERE username = 'new'") {
            it.get("cached_tokens", Integer::class.java)!!.toInt() to it.get("uncached_tokens", Integer::class.java)!!.toInt()
        }
        assertEquals(10 to 20, usage)
        assertEquals(5, executedSql().count { it.startsWith("ALTER TABLE") })
        LLMAccessRecordSchema(database).ensure()
        assertEquals(5, executedSql().count { it.startsWith("ALTER TABLE") })
    }

    @Test fun `concurrent completion and account initialization share one migration`() = runTest {
        val schema = LLMAccessRecordSchema(database)
        List(20) { async { schema.ensure() } }.awaitAll()
        assertEquals(1, executedSql().count { it.startsWith("CREATE TABLE") })
        assertEquals(0, executedSql().count { it.startsWith("ALTER TABLE") })
    }

    @Test fun `duplicate columns are identified through Spring wrappers by database codes`() {
        val sql = "ALTER TABLE llm_access_records ADD COLUMN source VARCHAR(32)"
        val duplicate = BadSqlGrammarException("execute", sql,
            R2dbcBadGrammarException("Duplicate column name 'source'", "42S21", 1060, sql))
        assertTrue(isDuplicateAccessRecordColumn(IllegalStateException("outer wrapper", duplicate)))
        val syntaxError = BadSqlGrammarException("execute", sql,
            R2dbcBadGrammarException("syntax error", "42000", 1064, sql))
        assertFalse(isDuplicateAccessRecordColumn(syntaxError))
        assertFalse(isDuplicateAccessRecordColumn(IllegalStateException("duplicate appears in an unrelated error")))
    }

    @Test fun `real migration errors propagate with database details and allow a subsequent retry`() = runTest {
        database.execute("CREATE TABLE llm_access_records (id BIGINT PRIMARY KEY, username VARCHAR(128))")
        val sql = "ALTER TABLE llm_access_records ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'unknown'"
        val failure = BadSqlGrammarException("execute", sql,
            R2dbcBadGrammarException("migration rejected", "42000", 1064, sql))
        Mockito.doThrow(failure).`when`(database).execute(sql)
        val schema = LLMAccessRecordSchema(database)
        val error = assertFailsWith<IllegalStateException> { schema.ensure() }
        assertTrue(error.message!!.contains("source"))
        assertTrue(error.message!!.contains("SQLSTATE=42000 code=1064: migration rejected"))
        Mockito.doCallRealMethod().`when`(database).execute(sql)
        schema.ensure()
        assertEquals(1, database.one("SELECT 1 FROM information_schema.columns WHERE table_name = 'llm_access_records' AND column_name = 'source'") { 1 })
    }

    private suspend fun insertRecord(username: String, source: String) {
        database.execute("""
            INSERT INTO llm_access_records(
                uid, username, source, source_identity, group_name, request_id,
                model, stream, status, cached_tokens, uncached_tokens, created_at
            ) VALUES (2, ?, ?, 'identity', 'group', 'new-request', 'test-model', FALSE, 'completed', 10, 20, 2000)
        """.trimIndent(), listOf(username, source))
    }
}
