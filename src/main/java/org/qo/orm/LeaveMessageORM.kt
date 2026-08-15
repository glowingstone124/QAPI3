package org.qo.orm

import org.qo.datas.ReactiveDatabase

class LeaveMessageORM() {
	private var databaseOverride: ReactiveDatabase? = null

	constructor(database: ReactiveDatabase) : this() {
		this.databaseOverride = database
	}

	private val database: ReactiveDatabase
		get() = reactiveDatabase(databaseOverride)

	companion object {
		private const val INSERT_MESSAGE_SQL =
			"INSERT INTO leavemessages (`from`, `to`, message) VALUES (?, ?, ?)"
		private const val SEARCH_MESSAGE_SQL =
			"SELECT `from`, `to`, message FROM leavemessages WHERE `from` = ? AND `to` = ?"
		private const val EXPLICIT_SENDER_QUERY_SQL =
			"SELECT `from`, `to`, message FROM leavemessages WHERE `from` = ?"
		private const val EXPLICIT_RECEIVER_QUERY_SQL =
			"SELECT `from`, `to`, `message` FROM leavemessages WHERE `to` = ?"
		private const val DELETE_SPECIFIED_MESSAGE_SQL =
			"DELETE FROM leavemessages WHERE `from` = ? AND `to` = ? AND message = ?"
	}

	suspend fun getDefinedSenderMessages(sender: String): List<LeaveMessage> = executeQuery(
		sql = EXPLICIT_SENDER_QUERY_SQL,
		bindings = listOf(sender),
	)

	suspend fun getDefinedReceiverMessages(receiver: String): List<LeaveMessage> = executeQuery(
		sql = EXPLICIT_RECEIVER_QUERY_SQL,
		bindings = listOf(receiver),
	)

	suspend fun searchMessages(sender: String, receiver: String): List<LeaveMessage> = executeQuery(
		sql = SEARCH_MESSAGE_SQL,
		bindings = listOf(sender, receiver),
	)

	suspend fun insertMessage(from: String, to: String, message: String): Long =
		if (database.execute(INSERT_MESSAGE_SQL, listOf(from, to, message)) == 1L) 1L else 0L

	suspend fun deleteSpecifiedSenderMessages(from: String, to: String, message: String) {
		database.execute(DELETE_SPECIFIED_MESSAGE_SQL, listOf(from, to, message))
	}

	private suspend fun executeQuery(sql: String, bindings: List<Any?>): List<LeaveMessage> =
		database.all(sql, bindings, ::mapRow)

	private fun mapRow(row: io.r2dbc.spi.Row): LeaveMessage = LeaveMessage(
		from = row.get("from", String::class.java).orEmpty(),
		to = row.get("to", String::class.java).orEmpty(),
		message = row.get("message", String::class.java).orEmpty(),
	)
}

data class LeaveMessage(
	val from: String,
	val to: String,
	val message: String,
)
