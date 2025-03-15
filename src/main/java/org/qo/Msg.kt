package org.qo

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import org.qo.loginService.Login
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.FileWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue

@Service
class Msg {
    companion object {
        const val MAX_QUEUE_SIZE = 300
        val msgQueue = LinkedBlockingQueue<Message>(MAX_QUEUE_SIZE)
        val gson = Gson()

        val login = Login()

        suspend fun sse(): SseEmitter {
            val emitter = SseEmitter(0L)
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            scope.launch {
                try {
                    while (true) {
                        val message = msgQueue.take()
                        emitter.send(JsonObject().apply {
                            addProperty("messages", gson.toJson(message))
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
                    addProperty("content", gson.toJson(msgQueue))
                }
            }.toString()
        }

        fun put(msg: String) {
            FileWriter("chathistory.txt", StandardCharsets.UTF_8).use { writer ->
                writer.write(msg)
                if (msgQueue.remainingCapacity() == 0) {
                    msgQueue.poll()
                }
                msgQueue.offer(Message(msg, 1, "Sender", System.currentTimeMillis()))
            }
        }

        fun putSys(msg: String) {
            val msgObj = Message(msg, 2, "System", System.currentTimeMillis())
            FileWriter("chathistory.txt", StandardCharsets.UTF_8).use { writer ->
                writer.write(gson.toJson(msgObj))
                generalPut(msgObj)
            }
        }

        fun generalPut(msg: Message) {
            if (msgQueue.remainingCapacity() == 0) {
                msgQueue.poll()
            }
            msgQueue.offer(msg)
        }

        fun putWebchat(msg: String, sender: String) {
            val msgObj = Message(msg, 3, "<Web>$sender", System.currentTimeMillis())
            generalPut(msgObj)
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
                arr.add(gson.toJson(item))
            }
            return arr
        }
    }
}

data class Message(
    val message: String,
    val from: Int,
    val sender: String,
    val time: Long
)
