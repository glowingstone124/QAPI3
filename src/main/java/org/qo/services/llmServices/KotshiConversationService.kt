package org.qo.services.llmServices

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.qo.datas.ReactiveDatabase
import org.qo.db.repository.KotshiConversationDbRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

data class KotshiConversation(
	val id: String,
	val uid: Long,
	val title: String,
	val model: String,
	val createdAt: Long,
	val updatedAt: Long,
)

data class KotshiMessage(
	val id: Long,
	val conversationId: String,
	val uid: Long,
	val role: String,
	val content: String,
	val createdAt: Long,
)

@Service
class KotshiConversationService @Autowired constructor(
	private val repository: KotshiConversationDbRepository,
) {
	constructor(database: ReactiveDatabase) : this(KotshiConversationDbRepository(database))

	private val initializationScope = CoroutineScope(SupervisorJob())
	private val schemaReady = CompletableDeferred<Unit>()

	@EventListener(ApplicationReadyEvent::class)
	fun initializeSchema() {
		initializationScope.launch {
			try {
				repository.ensureSchema()
				schemaReady.complete(Unit)
			} catch (error: Exception) {
				schemaReady.completeExceptionally(error)
				println("[Kotshi] conversation tables init failed: ${error.message}")
			}
		}
	}

	@PreDestroy
	fun shutdown() {
		initializationScope.cancel()
	}

	suspend fun listConversations(uid: Long): List<KotshiConversation> {
		schemaReady.await()
		return repository.listConversations(uid)
	}

	suspend fun getConversation(uid: Long, conversationId: String): KotshiConversation? {
		schemaReady.await()
		return repository.getConversation(uid, conversationId)
	}

	suspend fun createConversation(
		uid: Long,
		title: String? = null,
		model: String = "fast",
		customId: String? = null,
	): KotshiConversation {
		schemaReady.await()
		return repository.createConversation(uid, title, model, customId)
	}

	suspend fun updateConversation(
		uid: Long,
		conversationId: String,
		title: String? = null,
		model: String? = null,
	): Boolean {
		schemaReady.await()
		return repository.updateConversation(uid, conversationId, title, model)
	}

	suspend fun deleteConversation(uid: Long, conversationId: String): Boolean {
		schemaReady.await()
		return repository.deleteConversation(uid, conversationId)
	}

	suspend fun getMessages(uid: Long, conversationId: String): List<KotshiMessage> {
		schemaReady.await()
		return repository.getMessages(uid, conversationId)
	}

	suspend fun appendTurn(
		uid: Long,
		conversationId: String,
		userContent: String,
		assistantContent: String,
		model: String = "fast",
	) {
		schemaReady.await()
		val now = System.currentTimeMillis()
		val existing = getConversation(uid, conversationId)

		val autoTitle = if (existing == null || existing.title == "新对话" || existing.title.isBlank()) {
			userContent.lines().firstOrNull { it.isNotBlank() }?.trim()?.take(28) ?: "新对话"
		} else null

		if (existing == null) {
			createConversation(
				uid = uid,
				title = autoTitle,
				model = model,
				customId = conversationId,
			)
		} else if (autoTitle != null && autoTitle != existing.title) {
			updateConversation(uid, conversationId, title = autoTitle)
		} else {
			repository.touchConversation(uid, conversationId, now)
		}

		repository.appendTurnMessages(conversationId, uid, userContent, assistantContent, now)
	}
}
