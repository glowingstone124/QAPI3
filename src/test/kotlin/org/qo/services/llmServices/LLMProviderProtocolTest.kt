package org.qo.services.llmServices

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LLMProviderProtocolTest {
    @TempDir lateinit var tempDir: Path

    private fun configuration(): JsonObject = JsonParser.parseString("""
        {
          "defaultProvider": "mixed",
          "providers": {
            "mixed": {
              "chatCompletionsUrl": "https://gateway.example/chat",
              "responsesUrl": "https://gateway.example/responses",
              "anthropicUrl": "https://gateway.example/messages",
              "token": "test-token",
              "models": {
                "fast": {"model": "shared-model", "protocol": "chat-completions"},
                "thinking": {"model": "shared-model", "protocol": "responses"},
                "claude": {"model": "claude-model", "protocol": "anthropic", "thinkingMode": "adaptive"}
              },
              "summary": {"model": "claude"}
            }
          }
        }
    """).asJsonObject

    private fun load(root: JsonObject): LLMProvider {
        val file = tempDir.resolve("providers.json")
        Files.writeString(file, root.toString())
        return LLMProvider.fromConfig(file)
    }

    private fun provider(root: JsonObject) = root.getAsJsonObject("providers").getAsJsonObject("mixed")

    @Test fun `each preset selects its own protocol even with the same upstream model`() {
        val loaded = load(configuration())
        assertEquals(LLMProtocol.CHAT_COMPLETIONS, loaded.protocol("fast"))
        assertEquals(LLMProtocol.RESPONSES, loaded.protocol("thinking"))
        assertEquals(LLMProtocol.ANTHROPIC, loaded.protocol("claude"))
        assertEquals("claude-model", loaded.modelName("claude"))
        assertEquals("https://gateway.example/messages", loaded.summary.endpointUrl)
        assertEquals(LLMProtocol.ANTHROPIC, loaded.summary.protocol)
        assertEquals("adaptive", loaded.summary.thinkingMode)
        assertNull(loaded.balanceRelated.balanceUrl)
    }

    @Test fun `capability routes select different providers with their own prices`() {
        val root = configuration()
        val google = provider(root).deepCopy()
        google.getAsJsonObject("models").getAsJsonObject("thinking").apply {
            addProperty("model", "smart-model")
            add("pricing", JsonParser.parseString("""{"inputCnyPerMillion":2,"outputCnyPerMillion":6}"""))
        }
        root.getAsJsonObject("providers").add("google", google)
        root.add("routes", JsonParser.parseString("""{"fast":"mixed","thinking":"google"}"""))
        val snapshot = load(root)
        assertEquals("mixed", snapshot.forMode("fast").name)
        assertEquals("google", snapshot.forMode("thinking").name)
        assertEquals("smart-model", snapshot.forMode("thinking").modelName("thinking"))
        assertEquals(java.math.BigDecimal("6"), snapshot.forMode("thinking").modelConfig("thinking").pricing!!.outputCnyPerMillion)
    }

    @Test fun `all three endpoints are required including unused endpoints`() {
        for (key in listOf("responsesUrl", "chatCompletionsUrl", "anthropicUrl")) {
            val root = configuration()
            provider(root).remove(key)
            assertFailsWith<IllegalArgumentException> { load(root) }
        }
    }

    @Test fun `unused endpoints accept unavaliable but selected endpoints fail configuration`() {
        val root = configuration()
        val obj = provider(root)
        obj.getAsJsonObject("models").remove("claude")
        obj.remove("summary")
        obj.addProperty("anthropicUrl", "unavaliable")
        assertEquals("unavaliable", load(root).anthropicUrl)
        assertFailsWith<IllegalArgumentException> { load(root).endpoint(LLMProtocol.ANTHROPIC) }
        obj.addProperty("responsesUrl", "unavaliable")
        assertTrue(assertFailsWith<IllegalArgumentException> { load(root) }.message!!.contains("thinking"))
    }

    @Test fun `invalid unselected providers are rejected`() {
        val root = configuration()
        val invalid = provider(root).deepCopy()
        invalid.remove("anthropicUrl")
        root.getAsJsonObject("providers").add("invalid", invalid)
        assertFailsWith<IllegalArgumentException> { load(root) }
    }

    @Test fun `models must explicitly declare a known protocol`() {
        for (value in listOf(null, "unknown")) {
            val root = configuration()
            val model = provider(root).getAsJsonObject("models").getAsJsonObject("fast")
            if (value == null) model.remove("protocol") else model.addProperty("protocol", value)
            assertFailsWith<IllegalArgumentException> { load(root) }
        }
        val root = configuration()
        provider(root).getAsJsonObject("models").addProperty("fast", "legacy-model")
        assertFailsWith<IllegalArgumentException> { load(root) }
    }

    @Test fun `summary must use a declared model with an explicit protocol`() {
        val root = configuration()
        provider(root).getAsJsonObject("summary").addProperty("model", "undeclared-summary")
        assertFailsWith<IllegalArgumentException> { load(root) }
    }

    @Test fun `protocol endpoint changes are reloaded and invalid updates retain snapshot`() {
        val root = configuration()
        val file = tempDir.resolve("providers.json")
        Files.writeString(file, root.toString())
        ReloadableLLMProvider(file, reloadDelayMs = 10, pollIntervalMs = 25).use { providers ->
            providers.start()
            val fast = provider(root).getAsJsonObject("models").getAsJsonObject("fast")
            fast.addProperty("protocol", "anthropic")
            Files.writeString(file, root.toString())
            val deadline = System.nanoTime() + 2_000_000_000L
            while (System.nanoTime() < deadline && providers.current().protocol("fast") != LLMProtocol.ANTHROPIC) Thread.sleep(10)
            val snapshot = providers.current()
            assertEquals(LLMProtocol.ANTHROPIC, snapshot.protocol("fast"))
            provider(root).addProperty("anthropicUrl", "unavaliable")
            Files.writeString(file, root.toString())
            Thread.sleep(100)
            assertEquals(snapshot, providers.current())
        }
    }
}
