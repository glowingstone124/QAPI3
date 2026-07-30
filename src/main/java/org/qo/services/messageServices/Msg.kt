package org.qo.services.messageServices

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import org.qo.datas.ConnectionPool
import org.qo.utils.Logger
import org.qo.services.loginService.Login
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
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
        val tempQueue = LinkedBlockingQueue<Message>()
        val gson = Gson()

        val login = Login()
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

        fun put(msg: JsonObject) {
            FileWriter("chathistory.txt", StandardCharsets.UTF_8).use { writer ->
                writer.write(msg.toString())
                if (msgQueue.remainingCapacity() == 0) {
                    msgQueue.poll()
                }
                msgQueue.offer(gson.fromJson(msg, Message::class.java))
                //msgQueue.offer(Message(msg, 1, "Sender", System.currentTimeMillis()))
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

        fun getPublic(): JsonObject {
            return JsonObject().apply {
                val publicMessages = JsonArray()
                msgQueue.asSequence().filter { it.from != 2 }.forEach { publicMessages.add(gson.toJsonTree(it)) }
                add("messages", publicMessages)
                addProperty("empty", publicMessages.isEmpty)
            }
        }

        fun <T> LinkedBlockingQueue<T>.toJsonArray(): JsonArray {
            val arr = JsonArray()
            this.forEach { item ->
                arr.add(gson.toJsonTree(item))
            }
            return arr
        }

        fun init() {
            val connection = ConnectionPool.getConnection()
            ensureImagesColumn(connection)
            val sql = "SELECT message, from_user, sender, time, images FROM messages ORDER BY time DESC LIMIT $MAX_QUEUE_SIZE"
            var cnt = 0
            connection.use { conn ->
                try {
                    val statement: PreparedStatement = conn.prepareStatement(sql)

                    val messages = mutableListOf<Message>()
                    statement.use {
                        val resultSet: ResultSet = it.executeQuery()
                        resultSet.use { rs ->
                            while (rs.next()) {
                                val message = rs.getString("message")
                                val fromUser = rs.getInt("from_user")
                                val sender = rs.getString("sender")
                                val time = rs.getLong("time")
                                val images = parseImages(rs.getString("images"))
                                val msg = Message(message, fromUser, sender, time, images)
                                cnt++

                                messages.add(msg)
                            }
                        }
                    }

                    messages.sortBy { it.time }

                    messages.forEach { msgQueue.offer(it) }

                    Logger.log("Loaded $cnt messages from the database.", Logger.LogLevel.INFO)
                } catch (e: SQLException) {
                    e.printStackTrace()
                }
            }
        }

        private fun ensureImagesColumn(connection: java.sql.Connection) {
            val exists = connection.prepareStatement("SHOW COLUMNS FROM messages LIKE 'images'").use { statement ->
                statement.executeQuery().use { it.next() }
            }
            if (!exists) {
                connection.createStatement().use {
                    it.executeUpdate("ALTER TABLE messages ADD COLUMN images LONGTEXT NULL")
                }
            }
        }

        private fun parseImages(json: String?): List<String> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson<List<String>>(json, type).orEmpty()
            } catch (_: JsonSyntaxException) {
                emptyList()
            }
        }
    }
    @Scheduled(fixedRate = 10000)
    fun insertMessagesIntoSQL() {
        val connection = ConnectionPool.getConnection()
        val sql = "INSERT INTO messages (message, from_user, sender, time, images) VALUES (?, ?, ?, ?, ?)"

        connection.use { conn ->
            conn.autoCommit = false
            val messagesToInsert = mutableListOf<Message>()
            tempQueue.drainTo(messagesToInsert)
            try {
                if (messagesToInsert.isNotEmpty()) {
                    conn.prepareStatement(sql).use { statement ->
                        for (message in messagesToInsert) {
                            statement.setString(1, message.message)
                            statement.setInt(2, message.from)
                            statement.setString(3, message.sender)
                            statement.setLong(4, message.time)
                            statement.setString(5, gson.toJson(message.images))
                            statement.addBatch()
                        }
                        statement.executeBatch()
                    }
                }
                conn.commit()

            } catch (e: SQLException) {
                conn.rollback()
                messagesToInsert.forEach { tempQueue.offer(it) }
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
    val time: Long,
    val images: List<String> = emptyList(),
)
