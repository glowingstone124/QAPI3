package org.qo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class Request {
    fun sendPostRequest(target: String, data: String): String {
        var result = ""
        runBlocking(Dispatchers.IO) {
            val url: URI = URI.create(target)
            val connection: HttpURLConnection = url.toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doInput = true
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            DataOutputStream(connection.outputStream).use {
                it.writeBytes(data)
            }
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader(InputStreamReader(connection.inputStream)).use {
                    result = it.readText()
                }
            }
        }
        return result
    }
    fun sendGetRequest(target: String): String {
        var result = ""
        runBlocking(Dispatchers.IO) {
            val url: URI = URI.create(target)
            val connection: HttpURLConnection = url.toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.doInput = true
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader(InputStreamReader(connection.inputStream)).use {
                    result = it.readText()
                }
            }
        }
        return result
    }
    fun Download(url:String, path:String) {
        val url:URI = URI.create(url)
        val connection: HttpURLConnection = url.toURL().openConnection() as HttpURLConnection
        connection.inputStream.use {
            val targetPath = Path.of(path)
            Files.copy(it, targetPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
