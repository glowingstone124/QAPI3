package org.qo.services.messageServices

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.qo.TestApiApplication
import org.qo.datas.R2dbcDatabaseConfiguration
import org.qo.datas.ReactiveDatabase
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
	classes = [
		TestApiApplication::class,
		ReactiveDatabase::class,
		R2dbcDatabaseConfiguration::class,
		Msg::class,
	],
)
class MsgInitializationTest {
	@Autowired
	lateinit var database: ReactiveDatabase

	@Autowired
	lateinit var service: Msg

	@BeforeEach
	fun setUp() {
		clearQueues()
		runBlocking {
			database.execute("DROP TABLE IF EXISTS messages")
			database.execute(
				"""
				CREATE TABLE messages (
					message VARCHAR(255) NOT NULL,
					from_user INT NOT NULL,
					sender VARCHAR(255) NOT NULL,
					time BIGINT NOT NULL,
					images LONGTEXT NULL
				)
				""".trimIndent(),
			)
			database.execute(
				"INSERT INTO messages (message, from_user, sender, time, images) VALUES (?, ?, ?, ?, ?)",
				listOf("history", 1, "server", 1L, "[]"),
			)
		}
	}

	@AfterEach
	fun tearDown() = clearQueues()

	@Test
	fun `loading history preserves messages received during startup`() = runBlocking {
		Msg.generalPut(Message("live", 3, "web", 2L))

		service.loadMessagesFromDatabase()

		assertEquals(listOf("history", "live"), Msg.msgQueue.map { it.message })
	}

	private fun clearQueues() {
		Msg.msgQueue.clear()
		Msg.tempQueue.clear()
	}
}
