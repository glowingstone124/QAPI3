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

/** Watches replacements and polls for bind-mount, symlink, and related token-file updates. */
internal class ReloadableFileWatcher(
	private val file: Path,
	private val threadName: String,
	private val reloadDelayMs: Long,
	private val pollIntervalMs: Long,
	private val reload: (logInvalid: Boolean) -> Unit,
	private val onStartFailure: (Throwable) -> Unit,
	private val onLoopFailure: (Exception) -> Unit,
) : AutoCloseable {
	private val started = AtomicBoolean(false)
	private val closed = AtomicBoolean(false)
	@Volatile private var watchService: WatchService? = null

	fun start() {
		if (!started.compareAndSet(false, true)) return
		val parent = file.parent ?: return
		runCatching {
			FileSystems.getDefault().newWatchService().let { watcher ->
				try {
					parent.register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
					watchService = watcher
					Thread({ watchLoop(watcher) }, threadName).apply {
						isDaemon = true
						start()
					}
				} catch (e: Exception) {
					watcher.close()
					throw e
				}
			}
		}.onFailure(onStartFailure)
	}

	private fun watchLoop(watcher: WatchService) {
		try {
			while (!closed.get()) {
				val key = watcher.poll(pollIntervalMs, TimeUnit.MILLISECONDS)
				if (key == null) {
					reload(false)
					continue
				}
				val changed = key.pollEvents().any { event ->
					event.kind() == OVERFLOW || event.context() == file.fileName
				}
				if (!key.reset()) break
				if (changed) {
					if (reloadDelayMs > 0) Thread.sleep(reloadDelayMs)
					reload(true)
				}
			}
		} catch (_: ClosedWatchServiceException) {
			// Normal application shutdown.
		} catch (_: InterruptedException) {
			Thread.currentThread().interrupt()
		} catch (e: Exception) {
			if (!closed.get()) onLoopFailure(e)
		}
	}

	override fun close() {
		if (closed.compareAndSet(false, true)) watchService?.close()
	}
}
