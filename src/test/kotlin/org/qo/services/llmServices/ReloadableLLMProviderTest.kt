package org.qo.services.llmServices

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ReloadableLLMProviderTest {
	@TempDir
	lateinit var tempDir: Path

	@Test
	fun `reloads selected provider when default provider changes`() {
		val file = tempDir.resolve("providers.json")
		Files.writeString(file, config("first"))
		ReloadableLLMProvider(file, reloadDelayMs = 10, pollIntervalMs = 25).use { providers ->
			providers.start()
			assertEquals("first", providers.current().name)

			Files.writeString(file, config("second"))

			awaitProvider(providers, "second")
			assertEquals("second-fast", providers.current().fastModel)
		}
	}

	@Test
	fun `explicitly selected provider is retained across default changes`() {
		val file = tempDir.resolve("providers.json")
		Files.writeString(file, config("first"))
		ReloadableLLMProvider(file, explicitlySelected = "first", reloadDelayMs = 10, pollIntervalMs = 25).use { providers ->
			providers.start()
			Files.writeString(file, config("second"))
			Thread.sleep(100)

			assertEquals("first", providers.current().name)
		}
	}

	@Test
	fun `loads independent main and summary model context windows`() {
		val file = tempDir.resolve("providers.json")
		Files.writeString(file, """
			{
			  "defaultProvider": "first",
			  "providers": {
			    "first": {
			      "chatCompletionsUrl": "https://first.example/chat",
			      "responsesUrl": "https://first.example/responses",
			      "token": "first-token",
			      "balanceUrl": "https://first.example/balance",
			      "contextWindow": 65536,
			      "models": { "fast": "first-fast", "thinking": "first-thinking" },
			      "summary": { "model": "first-summary", "contextWindow": 8192 },
			      "compact": {
			        "enabled": true,
			        "triggerTurns": 10,
			        "triggerPercent": 65,
			        "keepTurns": 3,
			        "maxSummaryChars": 6000
			      }
			    }
			  }
			}
		""".trimIndent())

		val provider = LLMProvider.fromConfig(file)

		assertEquals(65536, provider.contextWindow)
		assertEquals("first-summary", provider.summaryModel)
		assertEquals(8192, provider.summaryContextWindow)
		assertEquals(10, provider.compact.triggerTurns)
		assertEquals(65, provider.compact.triggerPercent)
	}

	@Test
	fun `summary can use a preset from another provider`() {
		val file = tempDir.resolve("providers.json")
		Files.writeString(file, """
			{
			  "defaultProvider": "main",
			  "providers": {
			    "main": {
			      "chatCompletionsUrl": "https://main.example/chat", "responsesUrl": "https://main.example/responses",
			      "token": "main-token", "balanceUrl": "https://main.example/balance",
			      "models": { "fast": "main-fast", "thinking": "main-thinking" },
			      "summary": { "provider": "summary", "model": "compact", "contextWindow": 4096 }
			    },
			    "summary": {
			      "chatCompletionsUrl": "https://summary.example/chat", "responsesUrl": "https://summary.example/responses",
			      "token": "summary-token", "balanceUrl": "https://summary.example/balance",
			      "models": { "fast": "summary-fast", "thinking": "summary-thinking", "compact": "summary-compact" }
			    }
			  }
			}
		""".trimIndent())

		val provider = LLMProvider.fromConfig(file)

		assertEquals("summary", provider.summary.providerName)
		assertEquals("https://summary.example/chat", provider.summary.chatCompletionsUrl)
		assertEquals("summary-compact", provider.summaryModel)
	}

	private fun config(defaultProvider: String): String =
		"""
		{
		  "defaultProvider": "$defaultProvider",
		  "providers": {
		    "first": {
		      "chatCompletionsUrl": "https://first.example/chat",
		      "responsesUrl": "https://first.example/responses",
		      "token": "first-token",
		      "balanceUrl": "https://first.example/balance",
		      "models": { "fast": "first-fast", "thinking": "first-thinking" }
		    },
		    "second": {
		      "chatCompletionsUrl": "https://second.example/chat",
		      "responsesUrl": "https://second.example/responses",
		      "token": "second-token",
		      "balanceUrl": "https://second.example/balance",
		      "models": { "fast": "second-fast", "thinking": "second-thinking" }
		    }
		  }
		}
		""".trimIndent()

	private fun awaitProvider(providers: ReloadableLLMProvider, expected: String) {
		val deadline = System.nanoTime() + 2_000_000_000L
		while (System.nanoTime() < deadline && providers.current().name != expected) {
			Thread.sleep(20)
		}
		assertEquals(expected, providers.current().name)
	}
}
