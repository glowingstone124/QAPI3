package org.qo.services.llmServices

import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
	private val started = AtomicBoolean(false)
	private val closed = AtomicBoolean(false)
	@Volatile
	private var watchService: WatchService? = null

	fun current(): LLMProvider = current.get()

	fun start() {
		if (!started.compareAndSet(false, true)) return
		val parent = file.parent ?: return
		runCatching {
			FileSystems.getDefault().newWatchService().let { watcher ->
				try {
					parent.register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
					watchService = watcher
					Thread({ watchLoop(watcher) }, "llm-provider-watcher").apply {
						isDaemon = true
						start()
					}
				} catch (e: Exception) {
					watcher.close()
					throw e
				}
			}
		}.onFailure {
			println("[LLM] failed to watch provider configuration $file: ${it.message}")
		}
	}

	private fun watchLoop(watcher: WatchService) {
		try {
			while (!closed.get()) {
				// Polling also detects Docker bind-mount, ConfigMap/Secret, and token-file updates.
				val key = watcher.poll(pollIntervalMs, TimeUnit.MILLISECONDS)
				if (key == null) {
					reload(logInvalid = false)
					continue
				}
				var changed = false
				for (event in key.pollEvents()) {
					if (event.kind() == OVERFLOW || event.context() == file.fileName) changed = true
				}
				if (!key.reset()) break
				if (changed) {
					if (reloadDelayMs > 0) Thread.sleep(reloadDelayMs)
					reload(logInvalid = true)
				}
			}
		} catch (_: ClosedWatchServiceException) {
			// Normal application shutdown.
		} catch (_: InterruptedException) {
			Thread.currentThread().interrupt()
		} catch (e: Exception) {
			if (!closed.get()) println("[LLM] provider watcher stopped unexpectedly: ${e.message}")
		}
	}

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

	override fun close() {
		if (closed.compareAndSet(false, true)) watchService?.close()
	}
}
