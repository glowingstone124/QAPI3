package org.qo

import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture

class Request {
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun sendPostRequest(target: String, data: String): CompletableFuture<String> {
        val result = CompletableFuture<String>()
        coroutineScope.launch {
            try {
                val url = URI.create(target).toURL()
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doInput = true
                    doOutput = true
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

    fun download(url: String, path: String): CompletableFuture<Unit> {
        val result = CompletableFuture<Unit>()
        coroutineScope.launch {
            try {
                val targetUrl = URI.create(url).toURL()
                val connection = targetUrl.openConnection() as HttpURLConnection

                connection.inputStream.use { input ->
                    val targetPath = Path.of(path)
                    Files.copy(input, targetPath, StandardCopyOption.REPLACE_EXISTING)
                    result.complete(Unit)
                }
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
        return result
    }
}