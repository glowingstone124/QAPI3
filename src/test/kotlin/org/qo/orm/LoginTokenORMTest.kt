package org.qo.orm

import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.qo.datas.ReactiveDatabase
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoginTokenORMTest {
	private suspend fun database(): ReactiveDatabase {
		val factory = ConnectionFactories.get(
			"r2dbc:h2:mem:///tokens_${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
		)
		return ReactiveDatabase(DatabaseClient.create(factory), TransactionalOperator.create(R2dbcTransactionManager(factory))).also {
			it.execute("CREATE TABLE login_tokens (token VARCHAR(128) PRIMARY KEY, `user` VARCHAR(64), expires BIGINT)")
		}
	}

	@Test
	fun `legacy tokens migrate to game and new client IDs survive restart`(): Unit = runTest {
		val db = database()
		val old = UUID.randomUUID().toString()
		val fresh = UUID.randomUUID().toString()
		val expires = System.currentTimeMillis() + 60_000L
		db.execute("INSERT INTO login_tokens (token, `user`, expires) VALUES (?, 'legacy', ?)", listOf(old, expires))
		val orm = LoginTokenORM(db)
		assertEquals("game", orm.read(old)?.clientId)
		assertEquals("legacy", orm.read(old)?.user)
		orm.create(LoginToken(fresh, "new", expires, "qcommunity"))
		// Read the row directly so the shared token cache cannot mask an incorrect INSERT.
		assertEquals("qcommunity", db.one("SELECT client_id FROM login_tokens WHERE token = ?", listOf(fresh)) { it.get("client_id", String::class.java) })
		assertEquals("qcommunity", LoginTokenORM(db).read(fresh)?.clientId)
	}

	@Test
	fun `refresh persists expiry and never resurrects expired or deleted cached tokens`(): Unit = runTest {
		val db = database()
		val orm = LoginTokenORM(db)
		val token = UUID.randomUUID().toString()
		val now = System.currentTimeMillis()
		orm.create(LoginToken(token, "user", now + 60_000L, "qmod"))
		assertEquals("qmod", orm.extendExpiry(token, now + 604_800_000L, now)?.clientId)
		assertEquals(now + 604_800_000L, db.one("SELECT expires FROM login_tokens WHERE token = ?", listOf(token)) { longValue(it.get("expires")) })
		db.execute("UPDATE login_tokens SET expires = ? WHERE token = ?", listOf(now - 1, token))
		assertNull(orm.extendExpiry(token, now + 604_800_000L, now))
		val deleted = UUID.randomUUID().toString()
		orm.create(LoginToken(deleted, "user", now + 60_000L))
		db.execute("DELETE FROM login_tokens WHERE token = ?", listOf(deleted))
		assertNull(orm.extendExpiry(deleted, now + 604_800_000L, now))
		assertNull(orm.read(deleted))
	}

	@Test
	fun `expired tokens cannot be renewed and revoked tokens leave the cache`(): Unit = runTest {
		val db = database()
		val orm = LoginTokenORM(db)
		val token = UUID.randomUUID().toString()
		val now = System.currentTimeMillis()
		orm.create(LoginToken(token, "expired", now - 1, "qmod"))
		assertNull(orm.extendExpiry(token, now + 604_800_000L, now))
		assertNull(orm.read(token))
		orm.create(LoginToken(token, "active", now + 60_000L))
		assertTrue(orm.delete(token))
		assertNull(orm.read(token))
	}
}
