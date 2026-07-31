package org.qo.services.llmServices

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.test.Test
import kotlin.test.assertEquals

class ReloadableSystemPromptTest {
	@TempDir
	lateinit var tempDir: Path

	@Test
	fun `reloads prompt when file is modified`() {
		val file = tempDir.resolve("system-prompt.txt")
		Files.writeString(file, "first prompt")
		ReloadableSystemPrompt(null, file, "fallback", reloadDelayMs = 10, pollIntervalMs = 25).use { prompt ->
			prompt.start()
			assertEquals("first prompt", prompt.current())

			Files.writeString(file, "second prompt")

			awaitPrompt(prompt, "second prompt")
		}
	}

	@Test
	fun `loads prompt when missing file is created`() {
		val file = tempDir.resolve("system-prompt.txt")
		ReloadableSystemPrompt(null, file, "fallback", reloadDelayMs = 10, pollIntervalMs = 25).use { prompt ->
			prompt.start()
			assertEquals("fallback", prompt.current())

			Files.writeString(file, "created prompt")

			awaitPrompt(prompt, "created prompt")
		}
	}

	@Test
	fun `reloads prompt after atomic file replacement`() {
		val file = tempDir.resolve("system-prompt.txt")
		Files.writeString(file, "first prompt")
		ReloadableSystemPrompt(null, file, "fallback", reloadDelayMs = 10, pollIntervalMs = 25).use { prompt ->
			prompt.start()
			val replacement = tempDir.resolve("system-prompt.next")
			Files.writeString(replacement, "replacement prompt")
			runCatching { Files.move(replacement, file, ATOMIC_MOVE, REPLACE_EXISTING) }
				.getOrElse { Files.move(replacement, file, REPLACE_EXISTING) }

			awaitPrompt(prompt, "replacement prompt")
		}
	}

	@Test
	fun `blank file keeps previous valid prompt`() {
		val file = tempDir.resolve("system-prompt.txt")
		Files.writeString(file, "valid prompt")
		ReloadableSystemPrompt(null, file, "fallback", reloadDelayMs = 10, pollIntervalMs = 25).use { prompt ->
			prompt.start()
			Files.writeString(file, "   ")
			Thread.sleep(100)

			assertEquals("valid prompt", prompt.current())
		}
	}

	@Test
	fun `inline prompt takes precedence over watched file`() {
		val file = tempDir.resolve("system-prompt.txt")
		Files.writeString(file, "file prompt")
		ReloadableSystemPrompt("inline prompt", file, "fallback", reloadDelayMs = 10, pollIntervalMs = 25).use { prompt ->
			prompt.start()
			Files.writeString(file, "changed file prompt")
			Thread.sleep(100)

			assertEquals("inline prompt", prompt.current())
		}
	}

	private fun awaitPrompt(prompt: ReloadableSystemPrompt, expected: String) {
		val deadline = System.nanoTime() + 2_000_000_000L
		while (System.nanoTime() < deadline && prompt.current() != expected) {
			Thread.sleep(20)
		}
		assertEquals(expected, prompt.current())
	}
}
