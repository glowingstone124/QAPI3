package org.qo.orm

import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.qo.datas.Mapping.CardProfile
import org.qo.datas.ReactiveDatabase
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardProfileOrmTest {
	private lateinit var database: ReactiveDatabase
	private lateinit var orm: CardProfileOrm
	private val original = CardProfile("profile", 1, 1, 2, 3, "before", "1,2")

	@BeforeEach
	fun setUp() = runTest {
		val factory = ConnectionFactories.get("r2dbc:h2:mem:///card_profile_${System.nanoTime()};DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
		database = ReactiveDatabase(
			DatabaseClient.create(factory), TransactionalOperator.create(R2dbcTransactionManager(factory)),
		)
		database.execute("""
			CREATE TABLE card_profile (
				uuid VARCHAR(64) PRIMARY KEY, cardId BIGINT, statistic1 INT, statistic2 INT,
				statistic3 INT, avatar VARCHAR(64), owned VARCHAR(255)
			)
		""".trimIndent())
		orm = CardProfileOrm(database)
		orm.createAsync(original)
	}

	@Test
	fun `partial updates preserve omitted fields and accept zero and empty strings`() = runTest {
		val partial = CardProfile("profile", 0, null, 0, null, "", null)
		assertTrue(orm.updateAsync(partial))
		assertEquals(original.copy(cardId = 0, statistic2 = 0, avatar = ""), orm.readAsync("profile"))
		assertFalse(orm.updateAsync(partial.copy(uuid = "missing")))
	}

	@Test
	fun `an empty update does not access the database`() = runTest {
		database.execute("DROP TABLE card_profile")
		assertFalse(orm.updateAsync(CardProfile("profile", null, null, null, null, null, null)))
	}

	@Test
	fun `adding an owned card remains idempotent and preserves profile settings`() = runTest {
		assertTrue(orm.addCardToOwnedAsync("profile", 3))
		assertTrue(orm.addCardToOwnedAsync("profile", 3))
		assertEquals(original.copy(owned = "1,2,3"), orm.readAsync("profile"))
	}
}
