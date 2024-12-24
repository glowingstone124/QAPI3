package org.qo

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import org.json.JSONArray
import org.qo.server.MessageIn
import org.springframework.http.MediaType
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.FileWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue

class Msg {
    companion object {
        const val MAX_QUEUE_SIZE = 300
        val msgQueue = LinkedBlockingQueue<String>(MAX_QUEUE_SIZE)

        suspend fun sse(): SseEmitter {
            val emitter = SseEmitter(0L)
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            scope.launch {
                try {
                    if (!msgQueue.isEmpty()) {
                        val jsonArr = msgQueue.take()
                        emitter.send(JsonObject().apply {
                            addProperty("messages", jsonArr)
                        }, MediaType.APPLICATION_JSON)
                    }
                    while (true) {
                        val jsonArr = msgQueue.take()
                        emitter.send(JsonObject().apply {
                            addProperty("messages", jsonArr)
                        }, MediaType.APPLICATION_JSON)
                    }
                } catch (e: Exception) {
                    emitter.completeWithError(e)
                }
            }

            return emitter
        }
        fun webGet(): String {
            return JsonObject().apply {
                if (msgQueue.isEmpty()) {
                    addProperty("code", 400)
                } else {
                    addProperty("code", 0)
                    addProperty("content", msgQueue.peek())
                }
            }.toString()
        }
        fun put(msg: String) {
            val msgObj = JsonObject().apply {
                addProperty("message", msg)
                addProperty("from", 2)
                addProperty("sender", "System")
                addProperty("time", System.currentTimeMillis())
            }
            FileWriter("chathistory.txt", StandardCharsets.UTF_8).use { writer ->
                writer.write(msgObj.toString())
                if (msgQueue.remainingCapacity() == 0) {
                    msgQueue.poll()
                }
                msgQueue.offer(msgObj.toString())
            }
        }
        fun get(): JsonObject {
            return JsonObject().apply {
                add("messages", msgQueue.toJsonArray())
                addProperty("empty", msgQueue.isEmpty())
            }
        }
        fun <T> LinkedBlockingQueue<T>.toJsonArray(): JsonArray {
            val arr = JsonArray()
            this.forEach { item ->
                arr.add(item.toString())
            }
            return arr
        }
    }
}
