package org.qo.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

class Request {
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 5_000
        private const val READ_TIMEOUT_MILLIS = 15_000
    }

    fun sendPostRequest(target: String, data: String): CompletableFuture<String> {
        val result = CompletableFuture<String>()
        coroutineScope.launch {
            try {
                val url = URI.create(target).toURL()
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doInput = true
                    doOutput = true
                    connectTimeout = CONNECT_TIMEOUT_MILLIS
                    readTimeout = READ_TIMEOUT_MILLIS
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }

                DataOutputStream(connection.outputStream).use {
                    it.writeBytes(data)
                }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader(InputStreamReader(connection.inputStream)).use {
                        result.complete(it.readText())
                    }
                } else {
                    result.completeExceptionally(IOException("Failed to send POST request: ${connection.responseCode}"))
                }
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
        return result
    }

    fun sendGetRequest(target: String): CompletableFuture<String> {
        val result = CompletableFuture<String>()
        coroutineScope.launch {
            try {
                val url = URI.create(target).toURL()
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    doInput = true
                    connectTimeout = CONNECT_TIMEOUT_MILLIS
                    readTimeout = READ_TIMEOUT_MILLIS
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader(InputStreamReader(connection.inputStream)).use {
                        result.complete(it.readText())
                    }
                } else {
                    result.completeExceptionally(IOException("Failed to send GET request: ${connection.responseCode}"))
                }
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
        return result
    }

    fun download(url: String, path: String): CompletableFuture<Unit> =
        download(url, path, Long.MAX_VALUE)

    fun download(url: String, path: String, maxBytes: Long): CompletableFuture<Unit> {
        val result = CompletableFuture<Unit>()
        coroutineScope.launch {
            try {
                require(maxBytes > 0) { "maxBytes must be positive" }
                val targetUrl = URI.create(url).toURL()
                val connection = (targetUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MILLIS
                    readTimeout = READ_TIMEOUT_MILLIS
                }

                try {
                    if (connection.responseCode !in 200..299) {
                        throw IOException("Failed to download resource: ${connection.responseCode}")
                    }

                    val targetPath = Path.of(path)
                    targetPath.parent?.let { Files.createDirectories(it) }
                    connection.inputStream.use { input ->
                        Files.newOutputStream(targetPath).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var totalBytes = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (totalBytes > maxBytes - read) {
                                    throw IOException("Downloaded resource exceeds $maxBytes bytes")
                                }
                                output.write(buffer, 0, read)
                                totalBytes += read
                            }
                        }
                    }
                    result.complete(Unit)
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
        return result
    }
}
