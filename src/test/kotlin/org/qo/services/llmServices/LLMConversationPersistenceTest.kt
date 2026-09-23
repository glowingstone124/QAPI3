package org.qo.services.llmServices

import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.qo.TestApiApplication
import org.qo.datas.R2dbcDatabaseConfiguration
import org.qo.datas.ReactiveDatabase
import org.qo.db.repository.LlmConversationHistoryDbRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import kotlin.io.path.createTempDirectory

@SpringBootTest(classes = [TestApiApplication::class, ReactiveDatabase::class, R2dbcDatabaseConfiguration::class])
class LLMConversationPersistenceTest {
	@Autowired lateinit var database: ReactiveDatabase

	@Test
	fun `deleting a web conversation removes archived context and image files`() = runBlocking {
		val directory = createTempDirectory("qapi3-llm-delete-test")
		val key = "web:123:${UUID.randomUUID()}"
		val image = "data:image/png;base64," + Base64.getEncoder().encodeToString(byteArrayOf(5, 6, 7))
		val imageContent = JsonParser.parseString("""[{"type":"image_url","image_url":{"url":"$image"}}]""")
		val first = LLMConversationService(LLMImageStore.forTest(directory), LlmConversationHistoryDbRepository(database))
		try {
			first.append(key, imageContent, "answer")
			val archivedContent = database.one(
				"SELECT user_content FROM llm_conversation_turns WHERE conversation_key = ?",
				listOf(key),
			) { row -> row.get("user_content", String::class.java) }!!
			val imageId = JsonParser.parseString(archivedContent).asJsonArray[0].asJsonObject.get("image_id").asString
			val imagePath = directory.resolve("$imageId.bin")
			assertTrue(Files.exists(imagePath))
			first.shutdown()

			val second = LLMConversationService(LLMImageStore.forTest(directory), LlmConversationHistoryDbRepository(database))
			try {
				second.delete(key)
				assertEquals(0, second.historyMessages(key).size())
				assertFalse(Files.exists(imagePath))
			} finally {
				second.shutdown()
			}

			val third = LLMConversationService(LLMImageStore.forTest(directory), LlmConversationHistoryDbRepository(database))
			try {
				assertEquals(0, third.historyMessages(key).size())
				val remaining = database.one(
					"SELECT COUNT(*) AS total FROM llm_conversation_turns WHERE conversation_key = ?",
					listOf(key),
				) { row -> (row.get("total") as Number).toLong() }
				assertEquals(0L, remaining)
			} finally {
				third.shutdown()
			}
		} finally {
			first.shutdown()
			Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
		}
	}

	@Test
	fun `all source turns and compact context survive service restart`() = runBlocking {
		val directory = createTempDirectory("qapi3-llm-persistence-test")
		val repository = LlmConversationHistoryDbRepository(database)
		val first = LLMConversationService(LLMImageStore.forTest(directory), repository)
		try {
			val image = "data:image/png;base64," + Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
			val imageContent = JsonParser.parseString("""[{"type":"image_url","image_url":{"url":"$image"}}]""")
			first.append("qq:123:456", JsonPrimitive("qq question"), "qq answer")
			first.append("minecraft:123", imageContent, "minecraft answer")
			first.append("web:123:conv-1", JsonPrimitive("web question"), "web answer")
			repeat(13) { first.append("qq:compact", JsonPrimitive("question-$it"), "answer-$it") }
			assertTrue(first.compactIfNeeded("qq:compact", 524_288) { _, _ -> "earlier discussion" })
			first.shutdown()

			val second = LLMConversationService(
				LLMImageStore.forTest(directory), LlmConversationHistoryDbRepository(database),
			)
			try {
				assertEquals("qq question", second.historyMessages("qq:123:456")[0].asJsonObject.get("content").asString)
				assertEquals("web question", second.historyMessages("web:123:conv-1")[0].asJsonObject.get("content").asString)
				val restoredImage = second.historyMessages("minecraft:123")[0].asJsonObject
					.getAsJsonArray("content")[0].asJsonObject.getAsJsonObject("image_url").get("url").asString
				assertEquals(image, restoredImage)
				assertTrue(second.historyMessages("qq:compact")[0].asJsonObject.get("content").asString.contains("earlier discussion"))
				val archived = database.one("SELECT COUNT(*) AS total FROM llm_conversation_turns") {
					(it.get("total") as Number).toLong()
				}
				assertEquals(16L, archived)
			} finally {
				second.shutdown()
			}
		} finally {
			first.shutdown()
			Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
		}
	}
}
