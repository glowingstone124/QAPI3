package org.qo.db.repository

import com.google.gson.Gson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.qo.datas.ReactiveDatabase
import org.qo.services.messageServices.Message
import org.qo.services.messageServices.Msg
import org.springframework.stereotype.Repository

@Repository
class MessageDbRepository(
	private val database: ReactiveDatabase,
) {
	private val gson = Gson()
	private val schemaMigrationMutex = Mutex()

	suspend fun ensureHistoryColumns() {
		schemaMigrationMutex.withLock {
			ensureMessageCapacity()
			ensureColumn("images", "LONGTEXT NULL")
			ensureColumn("source_id", "VARCHAR(256) NULL")
		}
	}

	private suspend fun ensureMessageCapacity() {
		val maxLength = database.one(
			"""
			SELECT character_maximum_length FROM information_schema.columns
			WHERE (table_schema = DATABASE() OR table_catalog = DATABASE())
			  AND LOWER(table_name) = 'messages' AND LOWER(column_name) = 'message'
			LIMIT 1
			""".trimIndent(),
		) { row -> (row.get("character_maximum_length") as? Number)?.toLong() }
		if (maxLength != null && maxLength < 65_535L) {
			database.execute("ALTER TABLE messages MODIFY COLUMN message LONGTEXT NOT NULL")
		}
	}

	private suspend fun ensureColumn(name: String, definition: String) {
		val exists = runCatching { database.all("SELECT $name FROM messages WHERE 1 = 0") { Unit } }.isSuccess
		if (!exists) database.execute("ALTER TABLE messages ADD COLUMN $name $definition")
	}

	suspend fun loadRecentMessages(limit: Int): List<Message> {
		ensureHistoryColumns()
		return database.all(
			"SELECT message, from_user, sender, time, images, source_id FROM messages ORDER BY time DESC LIMIT $limit",
		) { row ->
			Message(
				message = row.get("message", String::class.java).orEmpty(),
				from = row.get("from_user", java.lang.Integer::class.java)!!.toInt(),
				sender = row.get("sender", String::class.java).orEmpty(),
				time = row.get("time", java.lang.Long::class.java)!!.toLong(),
				images = Msg.parseImages(row.get("images", String::class.java)),
				id = row.get("source_id", String::class.java),
			)
		}
	}

	suspend fun insertBatch(messages: List<Message>) {
		if (messages.isEmpty()) return
		ensureHistoryColumns()
		val placeholders = messages.joinToString(", ") { "(?, ?, ?, ?, ?, ?)" }
		database.inTransaction {
			database.execute(
				"INSERT INTO messages (message, from_user, sender, time, images, source_id) VALUES $placeholders",
				messages.flatMap { message ->
					listOf(
						message.message,
						message.from,
						message.sender,
						message.time,
						gson.toJson(message.images),
						message.id?.take(256),
					)
				},
			)
		}
	}
}
