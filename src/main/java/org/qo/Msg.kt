package org.qo

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import org.jetbrains.annotations.Async.Schedule
import org.qo.loginService.Login
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.FileWriter
import java.nio.charset.StandardCharsets
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.LinkedBlockingQueue

@Service
class Msg {
    companion object {
        const val MAX_QUEUE_SIZE = 300
        val msgQueue = LinkedBlockingQueue<Message>(MAX_QUEUE_SIZE)
        val tempQueue = ArrayList<Message>()
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
            tempQueue.add(msg)
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

        fun init() {
            val connection = ConnectionPool.getConnection()
            val sql = "SELECT message, from_user, sender, time FROM messages ORDER BY time DESC LIMIT $MAX_QUEUE_SIZE"
            var cnt = 0
            connection.use { conn ->
                try {
                    val statement: PreparedStatement = conn.prepareStatement(sql)
                    val resultSet: ResultSet = statement.executeQuery()

                    while (resultSet.next()) {
                        val message = resultSet.getString("message")
                        val fromUser = resultSet.getInt("from_user")
                        val sender = resultSet.getString("sender")
                        val time = resultSet.getLong("time")

                        val msg = Message(message, fromUser, sender, time)
                        cnt++
                        msgQueue.offer(msg)
                    }

                    Logger.log("Loaded $cnt messages from the database.", Logger.LogLevel.INFO)
                } catch (e: SQLException) {
                    e.printStackTrace()
                }
            }
        }
    }
    @Scheduled(fixedRate = 10000)
    fun insertMessagesIntoSQL() {
        val connection = ConnectionPool.getConnection()
        val sql = "INSERT INTO messages (message, from_user, sender, time) VALUES (?, ?, ?, ?)"

        connection.use { conn ->
            conn.autoCommit = false
            val statement: PreparedStatement = conn.prepareStatement(sql)
            try {
                val messagesToInsert = tempQueue.toList()
                if (messagesToInsert.isNotEmpty()) {
                    for (message in messagesToInsert) {
                        statement.setString(1, message.message)
                        statement.setInt(2, message.from)
                        statement.setString(3, message.sender)
                        statement.setLong(4, message.time)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                conn.commit()
                tempQueue.clear()

            } catch (e: SQLException) {
                conn.rollback()
                e.printStackTrace()
            } finally {
                conn.autoCommit = true
            }
        }
    }

}

data class Message(
    val message: String,
    val from: Int,
    val sender: String,
    val time: Long
)
