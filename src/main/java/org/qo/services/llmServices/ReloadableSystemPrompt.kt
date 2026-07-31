package org.qo.services.llmServices

import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
	private val started = AtomicBoolean(false)
	private val closed = AtomicBoolean(false)
	@Volatile
	private var watchService: WatchService? = null

	fun current(): String = current.get()

	fun start() {
		if (fixedPrompt != null || file == null || !started.compareAndSet(false, true)) {
			return
		}
		val parent = file.parent ?: return
		runCatching {
			FileSystems.getDefault().newWatchService().let { watcher ->
				try {
					parent.register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
					watchService = watcher
					Thread({ watchLoop(watcher) }, "llm-system-prompt-watcher").apply {
						isDaemon = true
						start()
					}
				} catch (e: Exception) {
					watcher.close()
					throw e
				}
			}
		}.onFailure {
			println("[LLM] failed to watch system prompt file $file: ${it.message}")
		}
	}

	private fun watchLoop(watcher: WatchService) {
		try {
			while (!closed.get()) {
				// Linux uses inotify here. The periodic read also covers Docker bind mounts
				// and Kubernetes ConfigMap/Secret updates that replace an inode or symlink.
				val key = watcher.poll(pollIntervalMs, TimeUnit.MILLISECONDS)
				if (key == null) {
					reload(logInvalid = false)
					continue
				}
				var promptChanged = false
				for (event in key.pollEvents()) {
					if (event.kind() == OVERFLOW || event.context() == file?.fileName) {
						promptChanged = true
					}
				}
				if (!key.reset()) {
					break
				}
				if (promptChanged) {
					if (reloadDelayMs > 0) Thread.sleep(reloadDelayMs)
					reload(logInvalid = true)
				}
			}
		} catch (_: ClosedWatchServiceException) {
			// Normal application shutdown.
		} catch (_: InterruptedException) {
			Thread.currentThread().interrupt()
		} catch (e: Exception) {
			if (!closed.get()) {
				println("[LLM] system prompt watcher stopped unexpectedly: ${e.message}")
			}
		}
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
		if (closed.compareAndSet(false, true)) {
			watchService?.close()
		}
	}
}
