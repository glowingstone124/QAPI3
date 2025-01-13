package org.qo

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import org.qo.loginService.Login
import org.qo.customAnnotations.Authority
import org.qo.loginService.AuthorityNeededServicesImpl
import org.springframework.beans.factory.annotation.Autowired
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
        val msgQueue = LinkedBlockingQueue<String>(MAX_QUEUE_SIZE)
        val login = Login()
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
            FileWriter("chathistory.txt", StandardCharsets.UTF_8).use { writer ->
                writer.write(msg)
                if (msgQueue.remainingCapacity() == 0) {
                    msgQueue.poll()
                }
                msgQueue.offer(msg)
            }
        }
        fun putSys(msg: String) {
            val msgObj = JsonObject().apply {
                addProperty("message", msg)
                addProperty("from", 2)
                addProperty("sender", "System")
                addProperty("time", System.currentTimeMillis())
            }
            FileWriter("chathistory.txt", StandardCharsets.UTF_8).use { writer ->
                writer.write(msgObj.toString())
                generalPut(msgObj.toString())
            }
        }
        fun generalPut(msg: String) {
            if (msgQueue.remainingCapacity() == 0) {
                msgQueue.poll()
            }
            msgQueue.offer(msg)
        }
        fun putWebchat(msg:String, sender:String) {
            val msgObj = JsonObject().apply {
                addProperty("message", msg)
                addProperty("from", 3)
                addProperty("sender", "<Web>$sender")
                addProperty("time", System.currentTimeMillis())
            }
            generalPut(msgObj.toString())
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
