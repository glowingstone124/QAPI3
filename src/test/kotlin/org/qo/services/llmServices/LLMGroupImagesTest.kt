package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LLMGroupImagesTest {
    @Test
    fun `server enforces qq group scope current message exclusion and ten latest images`() {
        val request = request((1..15).map { image(it) } + image(99, 43))
        val context = LLMGroupImages.consume(request, "qq", 42, 15)
        assertFalse(request.has("qbot_group_images"))
        assertEquals((5..14).map { "onebot:$it" }, context.metadata().map { it.asJsonObject.get("source_id").asString })
        assertEquals(10, context.metadata().size())
        for (source in listOf("web", "minecraft", null)) {
            val other = request(listOf(image(1)))
            assertEquals(0, LLMGroupImages.consume(other, source, 42, null).metadata().size())
            assertFalse(other.has("qbot_group_images"))
        }
    }

    @Test
    fun `attachment labels correspond to each image and historical data stays out of the current task`() {
        val images = LLMGroupImages.consume(request(listOf(image(1), image(2))), "qq", 42, null)
        val turn = LLMPromptCacheLayout.prepareCurrentTurn(
            JsonArray().apply { add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", "what is in those images?")
            }) },
            LLMPromptCacheLayout.Context(groupImages = images.metadata()),
        )
        images.attach(turn.messages)
        val parts = turn.messages[0].asJsonObject.getAsJsonArray("content")
        assertEquals(5, parts.size())
        val envelope = JsonParser.parseString(parts[0].asJsonObject.get("text").asString.substringAfter('\n')).asJsonObject
        assertEquals(listOf("what is in those images?"), envelope.getAsJsonObject("current_message").getAsJsonArray("text_parts").map { it.asString })
        val metadata = envelope.getAsJsonArray("group_history_images")
        for (index in 0..1) {
            val label = JsonParser.parseString(parts[index * 2 + 1].asJsonObject.get("text").asString.substringAfter('\n')).asJsonObject
            assertEquals(metadata[index], label)
            assertEquals("history-image-${index + 1}", label.get("image_id").asString)
            assertEquals("onebot:${index + 1}", label.get("source_id").asString)
            assertEquals((index + 1).toLong(), label.get("qquid").asLong)
            assertEquals("data:image/png;base64,${index + 1}", parts[index * 2 + 2].asJsonObject.getAsJsonObject("image_url").get("url").asString)
        }
        assertFalse(turn.persistedUserContent.toString().contains("data:image/"))
        assertTrue(turn.persistedUserContent.toString().contains("onebot:1"))
    }

    @Test
    fun `duplicates current images and unencoded urls are omitted`() {
        val request = request(listOf(image(1), image(1), image(2).apply { addProperty("image_url", "https://example.com/no-download.png") }))
        request.add("messages", JsonParser.parseString("""[{"role":"user","content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,1"}}]}]"""))
        assertEquals(0, LLMGroupImages.consume(request, "qq", 42, null).metadata().size())
    }

    @Test
    fun `missing image field leaves ordinary requests unchanged`() {
        val request = JsonObject()
        val context = LLMGroupImages.consume(request, "qq", 42, null)
        val messages = JsonParser.parseString("""[{"role":"user","content":"hello"}]""").asJsonArray
        context.attach(messages)
        assertEquals("hello", messages[0].asJsonObject.get("content").asString)
        assertEquals(0, context.metadata().size())
    }

    private fun request(images: List<JsonObject>) = JsonObject().apply {
        add("qbot_group_images", JsonArray().apply { images.forEach(::add) })
        add("messages", JsonArray())
    }

    private fun image(n: Int, groupId: Long = 42) = JsonObject().apply {
        addProperty("group_id", groupId)
        addProperty("source_id", "onebot:$n")
        addProperty("qquid", n)
        addProperty("nickname", "Alice$n")
        addProperty("time", n)
        addProperty("text", "image $n")
        addProperty("image_index", 1)
        addProperty("image_url", "data:image/png;base64,$n")
    }
}
