package org.qo.datas

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.qo.TestApiApplication
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
	classes = [
		TestApiApplication::class,
		ReactiveDatabase::class,
		R2dbcDatabaseConfiguration::class,
	],
)
class ReactiveDatabaseTransactionTest {
	@Autowired
	lateinit var database: ReactiveDatabase

	@BeforeEach
	fun setUp() {
		runBlocking {
			database.execute("DROP TABLE IF EXISTS reactive_transaction_test")
			database.execute("CREATE TABLE reactive_transaction_test (id INT PRIMARY KEY, amount INT NOT NULL)")
		}
	}

	@Test
	fun `commits after returning a transaction value`() = runBlocking {
		val result = database.inTransaction {
			database.execute("INSERT INTO reactive_transaction_test (id, amount) VALUES (?, ?)", listOf(1, 10))
			"committed"
		}

		assertEquals("committed", result)
		assertEquals(10, readValue())
	}

	@Test
	fun `rolls back when the transaction block fails`() {
		assertThrows(IllegalStateException::class.java) {
			runBlocking {
				database.inTransaction {
					database.execute("INSERT INTO reactive_transaction_test (id, amount) VALUES (?, ?)", listOf(1, 10))
					error("rollback")
				}
			}
		}

		runBlocking { assertEquals(null, readValue()) }
	}

	@Test
	fun `one returns the first row when a legacy query matches multiple rows`() = runBlocking {
		database.execute("INSERT INTO reactive_transaction_test (id, amount) VALUES (?, ?)", listOf(1, 10))
		database.execute("INSERT INTO reactive_transaction_test (id, amount) VALUES (?, ?)", listOf(2, 20))

		val result = database.one(
			"SELECT amount FROM reactive_transaction_test ORDER BY id",
		) { row -> row.get("amount", java.lang.Integer::class.java)?.toInt() }

		assertEquals(10, result)
	}

	private suspend fun readValue(): Int? = database.one(
		"SELECT amount FROM reactive_transaction_test WHERE id = 1",
	) { row -> row.get("amount", java.lang.Integer::class.java)?.toInt() }
}
