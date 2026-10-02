package org.qo.services.llmServices

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Keeps the selected provider as an atomic snapshot. A request takes one snapshot
 * before it is normalized, so a configuration reload cannot mix a model from one
 * provider with the URL or token of another one.
 */
class ReloadableLLMProvider(
	configPath: Path,
	private val explicitlySelected: String? = System.getenv("LLM_PROVIDER")?.trim()?.takeIf { it.isNotBlank() },
	private val reloadDelayMs: Long = 100,
	private val pollIntervalMs: Long = 500,
) : AutoCloseable {
	private val file = configPath.toAbsolutePath().normalize()
	private val current = AtomicReference(load())
	private val watcher = ReloadableFileWatcher(
		file, "llm-provider-watcher", reloadDelayMs, pollIntervalMs, ::reload,
		onStartFailure = { println("[LLM] failed to watch provider configuration $file: ${it.message}") },
		onLoopFailure = { println("[LLM] provider watcher stopped unexpectedly: ${it.message}") },
	)

	fun current(): LLMProvider = current.get()

	fun start() = watcher.start()

	private fun reload(logInvalid: Boolean) {
		val updated = runCatching(::load).getOrElse { error ->
			if (logInvalid) {
				println("[LLM] provider configuration $file is invalid or unreadable; keeping provider ${current().name}: ${error.message}")
			}
			return
		}
		val previous = current.getAndSet(updated)
		if (previous != updated) {
			println("[LLM] reloaded provider ${previous.name} -> ${updated.name} from $file")
		}
	}

	private fun load(): LLMProvider = LLMProvider.fromConfig(file, explicitlySelected)

	override fun close() = watcher.close()
}
