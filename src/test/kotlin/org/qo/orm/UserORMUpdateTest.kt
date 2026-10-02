package org.qo.orm

import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.test.runTest
import org.qo.datas.Mapping.Users
import org.qo.datas.ReactiveDatabase
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserORMUpdateTest {
	@Test
	fun `partial update keeps omitted values and accepts false and zero`() = runTest {
		val factory = ConnectionFactories.get("r2dbc:h2:mem:///user_update_${System.nanoTime()};DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
		val database = ReactiveDatabase(
			DatabaseClient.create(factory), TransactionalOperator.create(R2dbcTransactionManager(factory)),
		)
		database.execute("""
			CREATE TABLE users (
				uid BIGINT PRIMARY KEY, username VARCHAR(64), password VARCHAR(128),
				frozen BOOLEAN, remain INT, economy INT, signed BOOLEAN, playtime INT,
				temp BOOLEAN, invite INT, exp_level INT, score INT, damage BIGINT, last_login BIGINT
			)
		""".trimIndent())
		database.execute("""
			INSERT INTO users VALUES (123, 'before', 'old', TRUE, 3, 42, TRUE, 99, TRUE, 7, 8, 9, 10, 11)
		""".trimIndent())
		val orm = UserORM(database)
		val partial = Users(
			uid = 123, username = "after", password = "new", frozen = false, remain = 0,
			economy = null, signed = null, playtime = null, temp = null, invite = null,
			exp_level = null, score = null, damage = null, last_login = null,
		)
		assertTrue(orm.updateAsync(partial))
		val saved = requireNotNull(database.one("SELECT * FROM users WHERE uid = 123") { row ->
			listOf("username", "password", "frozen", "remain", "economy", "signed", "playtime",
				"temp", "invite", "exp_level", "score", "damage", "last_login").map { row.get(it) }
		})
		assertEquals(listOf("after", "new", false, 0, 42, true, 99, true, 7, 8, 9, 10L, 11L), saved)
		assertFalse(orm.updateAsync(partial.copy(uid = 456)))
	}
}
