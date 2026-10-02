package org.qo.orm

import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.qo.datas.Mapping.Users
import org.qo.datas.ReactiveDatabase
import org.springframework.r2dbc.BadSqlGrammarException
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserORMCacheInvalidationTest {
	private data class Fixture(val database: ReactiveDatabase, val orm: UserORM, val user: Users)

	enum class Field(val expectedValue: Any) {
		PASSWORD("new"), FROZEN(false), LEVEL(0), LAST_LOGIN(0L);

		suspend fun update(orm: UserORM, user: Users): Boolean = when (this) {
			PASSWORD -> orm.updatePasswordAsync(user.uid, "new")
			FROZEN -> orm.updateFrozenByUidAsync(user.uid, false)
			LEVEL -> orm.updateLevelByUsernameAsync(user.username, 0)
			LAST_LOGIN -> orm.updateLastLoginByUsernameAsync(user.username, 0L)
		}

		fun value(user: Users): Any? = when (this) {
			PASSWORD -> user.password
			FROZEN -> user.frozen
			LEVEL -> user.exp_level
			LAST_LOGIN -> user.last_login
		}
	}

	@ParameterizedTest
	@EnumSource(Field::class)
	fun `single field updates clear both user and profile caches`(field: Field): Unit = runTest {
		val fixture = fixture()
		assertTrue(field.update(fixture.orm, fixture.user))
		assertCachesCleared(fixture)
		val saved = requireNotNull(fixture.orm.readAsync(fixture.user.uid))
		assertEquals(field.expectedValue, field.value(saved))
		Field.entries.filter { it != field }.forEach { unchanged ->
			assertEquals(unchanged.value(fixture.user), unchanged.value(saved))
		}
	}

	@ParameterizedTest
	@EnumSource(Field::class)
	fun `updates with no matching row still clear caches`(field: Field): Unit = runTest {
		val fixture = fixture()
		fixture.database.execute("DELETE FROM users")
		assertFalse(field.update(fixture.orm, fixture.user))
		assertCachesCleared(fixture)
		assertNull(fixture.orm.readAsync(fixture.user.uid))
	}

	@ParameterizedTest
	@EnumSource(Field::class)
	fun `failed updates clear caches before propagating database error`(field: Field): Unit = runTest {
		val fixture = fixture()
		fixture.database.execute("DROP TABLE users")
		assertFailsWith<BadSqlGrammarException> { field.update(fixture.orm, fixture.user) }
		assertCachesCleared(fixture)
	}

	private fun assertCachesCleared(fixture: Fixture) {
		assertNull(fixture.orm.read(fixture.user.uid))
		assertNull(fixture.orm.read(fixture.user.username))
		assertFailsWith<UnsupportedOperationException> { fixture.orm.getProfileWithUser(fixture.user.username) }
		assertFailsWith<UnsupportedOperationException> { fixture.orm.getUserWithProfile(fixture.user.profile_id) }
	}

	private suspend fun fixture(): Fixture {
		val uid = System.nanoTime()
		val factory = ConnectionFactories.get("r2dbc:h2:mem:///user_cache_$uid;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
		val database = ReactiveDatabase(
			DatabaseClient.create(factory), TransactionalOperator.create(R2dbcTransactionManager(factory)),
		)
		database.execute("""
			CREATE TABLE users (
				uid BIGINT PRIMARY KEY, username VARCHAR(64), password VARCHAR(128), profile_id VARCHAR(64),
				frozen BOOLEAN, remain INT, economy INT, signed BOOLEAN, playtime INT, temp BOOLEAN,
				invite INT, exp_level INT, score INT, damage BIGINT DEFAULT 0, last_login BIGINT
			)
		""".trimIndent())
		val orm = UserORM(database)
		val user = Users(
			uid = uid, username = "cache_$uid", password = "old", profile_id = "profile_$uid",
			frozen = true, remain = 3, economy = 42, signed = true, playtime = 99, temp = true,
			invite = 7, exp_level = 8, score = 9, damage = 0L, last_login = 11L,
		)
		assertEquals(uid, orm.createAsync(user))
		assertEquals(user, orm.readAsync(uid))
		assertEquals(user, orm.read(user.username))
		assertEquals(user.profile_id, orm.getProfileWithUser(user.username))
		assertEquals(user.username, orm.getUserWithProfile(user.profile_id))
		return Fixture(database, orm, user)
	}
}
