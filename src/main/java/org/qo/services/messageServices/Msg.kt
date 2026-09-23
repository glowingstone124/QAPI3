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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.qo.datas.ReactiveDatabase
import org.qo.db.repository.MessageDbRepository
import org.qo.services.loginService.Login
import org.qo.utils.Logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

@Service
class Msg {
	private val repository: MessageDbRepository

	@Autowired
	constructor(database: ReactiveDatabase, @Autowired(required = false) repository: MessageDbRepository? = null) {
		this.repository = repository ?: MessageDbRepository(database)
	}

	constructor(repository: MessageDbRepository) {
		this.repository = repository
	}

	private val scope = CoroutineScope(SupervisorJob())
	private val flushMutex = Mutex()
	private val shuttingDown = AtomicBoolean(false)

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
			generalPut(Message(
				message = msg.get("message")?.asString.orEmpty(),
				from = msg.get("from")?.asInt ?: 0,
				sender = msg.get("sender")?.asString.orEmpty(),
				time = msg.get("time")?.asLong ?: System.currentTimeMillis(),
				images = parseImages(msg.get("images")?.toString()),
				id = msg.get("id")?.takeIf { !it.isJsonNull }?.asString,
			))
		}

		fun put(message: String, from: Int, sender: String, time: Long) {
			val msg = JsonObject().apply {
				addProperty("message", message)
				addProperty("from", from)
				addProperty("sender", sender)
				addProperty("time", time)
			}
			put(msg)
		}

		fun putSys(message: String) {
			put(message, 2, "System", System.currentTimeMillis())
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

		fun get(): JsonObject = getJson()

		fun getJson(): JsonObject {
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
		shuttingDown.set(true)
		runCatching {
			runBlocking {
				withTimeout(30_000) { flushPending() }
			}
		}.onFailure {
			Logger.log("Failed to persist ${tempQueue.size} pending messages during shutdown: ${it.message}", Logger.LogLevel.ERROR)
		}
		scope.cancel()
	}

	internal suspend fun loadMessagesFromDatabase() {
		val messages = repository.loadRecentMessages(MAX_QUEUE_SIZE)
		synchronized(queueLock) {
			val persisted = messages.mapTo(HashSet()) { it.persistenceKey() }
			val liveMessages = msgQueue.filterNot { it.persistenceKey() in persisted }
			val merged = (messages + liveMessages)
				.sortedBy { it.time }
				.takeLast(MAX_QUEUE_SIZE)
			msgQueue.clear()
			merged.forEach { msgQueue.offer(it) }
		}
		Logger.log("Loaded ${messages.size} messages from the database.", Logger.LogLevel.INFO)
	}

	@Scheduled(fixedRate = 10000)
	fun insertMessagesIntoSQL() {
		if (shuttingDown.get()) return
		scope.launch {
			flushPending()
		}
	}

	internal suspend fun flushPending() = flushMutex.withLock {
		val messagesToInsert = mutableListOf<Message>()
		tempQueue.drainTo(messagesToInsert)
		try {
			if (messagesToInsert.isEmpty()) return@withLock
			repository.insertBatch(messagesToInsert)
		} catch (error: Exception) {
			messagesToInsert.forEach { tempQueue.offer(it) }
			Logger.log("Failed to persist ${messagesToInsert.size} messages: ${error.message}", Logger.LogLevel.ERROR)
			throw error
		}
	}

	private fun Message.persistenceKey(): String = id?.let { "id:$from:$it" }
		?: "content:$from:$sender:$time:$message:${images.joinToString()}"
}

data class Message(
	val message: String,
	val from: Int,
	val sender: String,
	val time: Long,
	val images: List<String> = emptyList(),
	val id: String? = null,
)
