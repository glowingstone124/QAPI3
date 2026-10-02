package org.qo.services.llmServices

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReloadableFileWatcherTest {
	@TempDir
	lateinit var tempDir: Path

	@Test
	fun `start is idempotent and close stops the named daemon thread`() {
		val file = tempDir.resolve("watched.txt")
		Files.writeString(file, "first")
		val threads = ConcurrentHashMap.newKeySet<Thread>()
		val failures = ConcurrentLinkedQueue<Throwable>()
		val started = CountDownLatch(1)
		val reads = AtomicInteger()
		ReloadableFileWatcher(
			file, "test-reload-watcher", 10, 25,
			reload = {
				threads.add(Thread.currentThread())
				reads.incrementAndGet()
				started.countDown()
			},
			onStartFailure = { failures.add(it) },
			onLoopFailure = { failures.add(it) },
		).use { watcher ->
			watcher.start()
			watcher.start()
			assertTrue(started.await(2, TimeUnit.SECONDS))
			val thread = threads.single()
			assertEquals("test-reload-watcher", thread.name)
			assertTrue(thread.isDaemon)

			watcher.close()
			watcher.close()
			thread.join(2_000)
			assertFalse(thread.isAlive)
			val afterClose = reads.get()
			Files.writeString(file, "second")
			watcher.start()
			Thread.sleep(100)
			assertEquals(afterClose, reads.get())
			assertTrue(failures.isEmpty())
		}
	}
}
