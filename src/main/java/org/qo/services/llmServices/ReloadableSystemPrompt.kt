package org.qo.services.llmServices

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

class ReloadableSystemPrompt(
	inlinePrompt: String?,
	promptFile: Path?,
	fallbackPrompt: String,
	private val reloadDelayMs: Long = 100,
	private val pollIntervalMs: Long = 500,
) : AutoCloseable {
	private val fixedPrompt = inlinePrompt?.trim()?.takeIf { it.isNotBlank() }
	private val file = promptFile?.toAbsolutePath()?.normalize()
	private val current = AtomicReference(fixedPrompt ?: readPromptFile() ?: fallbackPrompt)
	private val watcher = file?.takeIf { fixedPrompt == null }?.let { path ->
		ReloadableFileWatcher(
			path, "llm-system-prompt-watcher", reloadDelayMs, pollIntervalMs, ::reload,
			onStartFailure = { println("[LLM] failed to watch system prompt file $file: ${it.message}") },
			onLoopFailure = { println("[LLM] system prompt watcher stopped unexpectedly: ${it.message}") },
		)
	}

	fun current(): String = current.get()

	fun start() {
		watcher?.start()
	}

	private fun reload(logInvalid: Boolean) {
		val updated = readPromptFile()
		if (updated == null) {
			if (logInvalid) {
				println("[LLM] system prompt file $file is missing, unreadable, or blank; keeping the previous prompt")
			}
			return
		}
		if (current.getAndSet(updated) != updated) {
			println("[LLM] reloaded system prompt from $file")
		}
	}

	private fun readPromptFile(): String? = file?.let { path ->
		runCatching { Files.readString(path).trim() }
			.getOrNull()
			?.takeIf { it.isNotBlank() }
	}

	override fun close() {
		watcher?.close()
	}
}
