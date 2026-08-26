package org.qo.services.llmServices

import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LLMConversationServiceTest {
    @Test
    fun `compacts older turns and keeps a rolling summary with recent turns`() = runBlocking {
        val dir = createTempDirectory("qapi3-llm-compact-test")
        try {
            val service = LLMConversationService(LLMImageStore.forTest(dir))
            repeat(13) { turn ->
                service.append("qq:compact", JsonPrimitive("question-$turn"), "answer-$turn")
            }

            var compactedMessages = 0
            val compacted = service.compactIfNeeded("qq:compact", 524_288) { existingSummary, messages ->
                assertEquals(null, existingSummary)
                compactedMessages = messages.size()
                "Earlier discussion summary"
            }

            assertTrue(compacted)
            assertEquals(18, compactedMessages)
            val history = service.historyMessages("qq:compact")
            assertEquals(9, history.size())
            assertEquals("user", history[0].asJsonObject.get("role").asString)
            assertTrue(history[0].asJsonObject.get("content").asString.contains("Earlier discussion summary"))
            assertEquals("question-9", history[1].asJsonObject.get("content").asString)
        } finally {
            Files.walk(dir).use { files ->
                files.sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `stores data url outside conversation content and restores it for history`() {
        val dir = createTempDirectory("qapi3-llm-image-test")
        try {
            val imageStore = LLMImageStore.forTest(dir)
            val service = LLMConversationService(imageStore)
            val dataUrl = "data:image/png;base64," +
                Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))

            val userContent = JsonParser.parseString(
                """
                [
                  {"type":"text","text":"这是什么？"},
                  {"type":"image_url","image_url":{"url":"$dataUrl","detail":"high"}}
                ]
                """.trimIndent()
            )

            service.append("qq:123:456", userContent, "我看到了。")

            // One image payload is persisted outside the in-memory message JSON.
            Files.list(dir).use { files ->
                assertEquals(1L, files.count())
            }

            val history = service.historyMessages("qq:123:456")
            assertEquals(2, history.size())

            val restored = history[0].asJsonObject.getAsJsonArray("content")
            assertEquals("text", restored[0].asJsonObject.get("type").asString)
            assertEquals("image_url", restored[1].asJsonObject.get("type").asString)
            assertEquals(
                dataUrl,
                restored[1].asJsonObject
                    .getAsJsonObject("image_url")
                    .get("url")
                    .asString
            )
            assertEquals(
                "high",
                restored[1].asJsonObject
                    .getAsJsonObject("image_url")
                    .get("detail")
                    .asString
            )
        } finally {
            Files.walk(dir).use { files ->
                files.sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `keeps remote image url as a normal history part`() {
        val dir = createTempDirectory("qapi3-llm-image-url-test")
        try {
            val imageStore = LLMImageStore.forTest(dir)
            val service = LLMConversationService(imageStore)
            val userContent = JsonParser.parseString(
                """
                [
                  {
                    "type":"image_url",
                    "image_url":{"url":"https://example.com/a.png"}
                  }
                ]
                """.trimIndent()
            )

            service.append("qq:1:2", userContent, "ok")
            val history = service.historyMessages("qq:1:2")
            val url = history[0].asJsonObject
                .getAsJsonArray("content")[0]
                .asJsonObject
                .getAsJsonObject("image_url")
                .get("url")
                .asString

            assertEquals("https://example.com/a.png", url)
            Files.list(dir).use { files ->
                assertTrue(files.findAny().isEmpty)
            }
        } finally {
            Files.walk(dir).use { files ->
                files.sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
