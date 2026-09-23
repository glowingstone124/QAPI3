package org.qo.services.messageServices

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

	@Test
	fun `loading history adds images column to legacy table`() = runBlocking {
		database.execute("DROP TABLE messages")
		database.execute(
			"""
				CREATE TABLE messages (
					message VARCHAR(255) NOT NULL,
					from_user INT NOT NULL,
					sender VARCHAR(255) NOT NULL,
					time BIGINT NOT NULL
				)
			""".trimIndent(),
		)
		database.execute(
			"INSERT INTO messages (message, from_user, sender, time) VALUES (?, ?, ?, ?)",
			listOf("legacy", 1, "server", 1L),
		)

		service.loadMessagesFromDatabase()

		assertEquals(listOf("legacy"), Msg.msgQueue.map { it.message })
		assertEquals(emptyList<String>(), Msg.msgQueue.single().images)
	}

	@Test
	fun `all message sources survive restart and system messages stay private`() = runBlocking {
		Msg.put(JsonObject().apply {
			addProperty("id", "onebot:42")
			addProperty("message", "qq")
			addProperty("from", 0)
			addProperty("sender", "qq-user")
			addProperty("time", 10L)
		})
		Msg.generalPut(Message("minecraft", 1, "player", 11L))
		Msg.putSys("private system message")
		Msg.putWebchat("web", "browser-user")
		Msg.generalPut(Message("creative", 4, "builder", 12L))

		service.flushPending()
		service.loadMessagesFromDatabase()
		assertEquals(5, Msg.msgQueue.count { it.message in setOf("qq", "minecraft", "private system message", "web", "creative") })

		Msg.msgQueue.clear()
		service.loadMessagesFromDatabase()
		val restored = Msg.msgQueue.toList()
		assertEquals(setOf(0, 1, 2, 3, 4), restored.map { it.from }.toSet())
		assertEquals("onebot:42", restored.single { it.message == "qq" }.id)
		assertFalse(Msg.getPublic().getAsJsonArray("messages").any { it.asJsonObject.get("message").asString == "private system message" })
	}

	@Test
	fun `shutdown flushes pending messages`() = runBlocking {
		Msg.generalPut(Message("pending", 3, "web", 20L))
		Msg(database).shutdown()
		Msg.msgQueue.clear()
		service.loadMessagesFromDatabase()
		assertEquals("pending", Msg.msgQueue.single { it.message == "pending" }.message)
	}

	@Test
	fun `legacy message column expands so long messages survive restart`() = runBlocking {
		val longMessage = "history-" + "字".repeat(300)
		Msg.generalPut(Message(longMessage, 1, "player", 21L))
		service.flushPending()
		Msg.msgQueue.clear()
		service.loadMessagesFromDatabase()
		assertEquals(longMessage, Msg.msgQueue.single { it.message == longMessage }.message)
	}

	private fun clearQueues() {
		Msg.msgQueue.clear()
		Msg.tempQueue.clear()
	}
}
