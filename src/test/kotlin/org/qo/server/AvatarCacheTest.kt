package org.qo.server

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AvatarCacheTest {
    @TempDir
    lateinit var cacheDirectory: Path

    private lateinit var server: HttpServer
    private val requests = AtomicInteger()
    private val blockDownloads = AtomicBoolean(false)
    private val firstRequest = CountDownLatch(1)
    private val releaseDownload = CountDownLatch(1)
    private val payload = byteArrayOf(137.toByte(), 80, 78, 71, 1, 2, 3)

    @BeforeEach
    fun setUp() {
        AvatarCache.init(cacheDirectory)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/avatar") { exchange ->
            requests.incrementAndGet()
            if (blockDownloads.get()) {
                firstRequest.countDown()
                releaseDownload.await(2, TimeUnit.SECONDS)
            }
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `cache writes a complete normalized avatar file`() {
        AvatarCache.cache(downloadUrl(), "Alex_123")

        assertTrue(AvatarCache.has("alex_123"))
        assertEquals(payload.toList(), Files.readAllBytes(cacheDirectory.resolve("alex_123.png")).toList())
        assertEquals(payload.toList(), AvatarCache.read("ALEX_123")!!.toList())
        assertEquals("/qo/download/avatar/image?name=alex_123", AvatarCache.url("Alex_123"))
    }

    @Test
    fun `invalid names cannot escape the cache directory`() {
        assertFalse(AvatarCache.has("../outside"))
        assertFalse(AvatarCache.has("a/b"))
        assertTrue(AvatarCache.cacheAsync(downloadUrl(), "../outside").isCompletedExceptionally)
        assertEquals(0, requests.get())
    }

    @Test
    fun `concurrent requests share one download`() {
        blockDownloads.set(true)
        val futures = (1..8).map {
            CompletableFuture.supplyAsync { AvatarCache.cacheAsync(downloadUrl(), "Alex_123").join() }
        }

        assertTrue(firstRequest.await(2, TimeUnit.SECONDS))
        releaseDownload.countDown()
        futures.forEach { it.join() }

        assertEquals(1, requests.get())
        assertTrue(AvatarCache.has("Alex_123"))
    }

    @Test
    fun `external special avatar uses an isolated cache key`() {
        val sourceUrl = "https://bucket.glowingstone.cn/avatars/pixel_artworks/koishi.png"
        val key = AvatarCache.externalKey(sourceUrl)

        assertTrue(AvatarCache.isValidCacheKey(key))
        AvatarCache.cacheAsyncForKey(downloadUrl(), key).join()

        assertTrue(AvatarCache.isFreshKey(key))
        assertEquals(payload.toList(), AvatarCache.readKey(key)!!.toList())
        assertEquals("/qo/download/avatar/image?key=$key", AvatarCache.urlForKey(key, null))
        assertFalse(AvatarCache.has("glowingstone124"))
    }

    private fun downloadUrl(): String = "http://127.0.0.1:${server.address.port}/avatar"
}
