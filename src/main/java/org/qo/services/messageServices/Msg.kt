package org.qo.services.messageServices

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.qo.datas.ReactiveDatabase
import org.qo.services.loginService.Login
import org.qo.utils.Logger
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.FileWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

@Service
class Msg(
	private val database: ReactiveDatabase,
) {
	private val scope = CoroutineScope(SupervisorJob())
	private val flushing = AtomicBoolean(false)
	private val schemaMigrationMutex = Mutex()

	companion object {
		const val MAX_QUEUE_SIZE = 300
		val msgQueue = LinkedBlockingQueue<Message>(MAX_QUEUE_SIZE)
		val tempQueue = LinkedBlockingQueue<Message>()
		val gson = Gson()
		val login = Login()
		private val queueLock = Any()

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
				synchronized(queueLock) {
					if (msgQueue.remainingCapacity() == 0) {
						msgQueue.poll()
					}
					msgQueue.offer(gson.fromJson(msg, Message::class.java))
				}
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
			synchronized(queueLock) {
				if (msgQueue.remainingCapacity() == 0) {
					msgQueue.poll()
				}
				tempQueue.add(msg)
				msgQueue.offer(msg)
			}
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
			forEach { item -> arr.add(gson.toJsonTree(item)) }
			return arr
		}

		fun parseImages(json: String?): List<String> {
			if (json.isNullOrBlank()) return emptyList()
			return try {
				val type = object : TypeToken<List<String>>() {}.type
				gson.fromJson<List<String>>(json, type).orEmpty()
			} catch (_: JsonSyntaxException) {
				emptyList()
			}
		}
	}

	@EventListener(ApplicationReadyEvent::class)
	fun initialize() {
		scope.launch {
			runCatching { loadMessagesFromDatabase() }
				.onFailure { Logger.log("Failed to load messages from the database: ${it.message}", Logger.LogLevel.ERROR) }
		}
	}

	@PreDestroy
	fun shutdown() {
		scope.cancel()
	}

	internal suspend fun loadMessagesFromDatabase() {
		ensureImagesColumn()
		val messages = database.all(
			"SELECT message, from_user, sender, time, images FROM messages ORDER BY time DESC LIMIT $MAX_QUEUE_SIZE",
		) { row ->
			Message(
				message = row.get("message", String::class.java).orEmpty(),
				from = row.get("from_user", java.lang.Integer::class.java)!!.toInt(),
				sender = row.get("sender", String::class.java).orEmpty(),
				time = row.get("time", java.lang.Long::class.java)!!.toLong(),
				images = parseImages(row.get("images", String::class.java)),
			)
		}
		synchronized(queueLock) {
			val liveMessages = msgQueue.toList()
			val merged = (messages + liveMessages)
				.sortedBy { it.time }
				.takeLast(MAX_QUEUE_SIZE)
			msgQueue.clear()
			merged.forEach { msgQueue.offer(it) }
		}
		Logger.log("Loaded ${messages.size} messages from the database.", Logger.LogLevel.INFO)
	}

	private suspend fun ensureImagesColumn() {
		schemaMigrationMutex.withLock {
			val columnExists = runCatching {
				database.all("SELECT images FROM messages WHERE 1 = 0") { Unit }
			}.isSuccess
			if (!columnExists) {
				database.execute("ALTER TABLE messages ADD COLUMN images LONGTEXT NULL")
			}
		}
	}

	@Scheduled(fixedRate = 10000)
	fun insertMessagesIntoSQL() {
		if (!flushing.compareAndSet(false, true)) return
		scope.launch {
			val messagesToInsert = mutableListOf<Message>()
			tempQueue.drainTo(messagesToInsert)
			try {
				if (messagesToInsert.isEmpty()) return@launch
				ensureImagesColumn()
				val placeholders = messagesToInsert.joinToString(", ") { "(?, ?, ?, ?, ?)" }
				database.inTransaction {
					database.execute(
						"INSERT INTO messages (message, from_user, sender, time, images) VALUES $placeholders",
						messagesToInsert.flatMap { message ->
							listOf(
								message.message,
								message.from,
								message.sender,
								message.time,
								gson.toJson(message.images),
							)
						},
					)
				}
			} catch (error: Exception) {
				messagesToInsert.forEach { tempQueue.offer(it) }
				error.printStackTrace()
			} finally {
				flushing.set(false)
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
	val id: String? = null,
)
