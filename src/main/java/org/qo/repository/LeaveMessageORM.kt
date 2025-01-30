package org.qo.orm

import io.r2dbc.spi.Connection
import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle

class LeaveMessageORM {
	companion object {
		private const val INSERT_MESSAGE_SQL =
			"INSERT INTO leavemessages (`from`, `to`, message) VALUES  (:from, :to, :message)"
		private const val SEARCH_MESSAGE_SQL =
			"SELECT `from`, `to`, message FROM leavemessages WHERE `from` = :from AND `to` = :to"
		private const val EXPLICIT_SENDER_QUERY_SQL =
			"SELECT `from`, `to`, message FROM leavemessages WHERE `from` = :from"
		private const val EXPLICIT_RECEIVER_QUERY_SQL =
			"SELECT `from`, `to`, `message` FROM leavemessages WHERE `to` = :to"
		private const val DELETE_SPECIFIED_MESSAGE_SQL =
			"DELETE FROM leavemessages WHERE `from` = :from, `to` = :to, message = :message"
	}


	suspend fun getDefinedSenderMessages(sender: String): List<LeaveMessage> {
		val connection = SQL.getConnection()

		return connection.createStatement(EXPLICIT_SENDER_QUERY_SQL).bind("from", sender).execute().awaitSingle()
			.map { row: Row, _: RowMetadata ->
				LeaveMessage(
					from = row.get("from", String::class.java) ?: "",
					to = row.get("to", String::class.java) ?: "",
					message = row.get("message", String::class.java) ?: ""
				)
			}.asFlow().toList()
	}

	suspend fun deleteSpecifiedSenderMessages(from: String, to: String, message: String){
		val connection = SQL.getConnection()
		connection.createStatement(DELETE_SPECIFIED_MESSAGE_SQL).bind("from", from).bind("to", to).bind("message", message).execute().awaitSingle()
	}

	suspend fun getDefinedReceiverMessages(receiver: String): List<LeaveMessage> {
		val connection = SQL.getConnection()
		return connection.createStatement(EXPLICIT_RECEIVER_QUERY_SQL).bind("to", receiver).execute().awaitSingle()
			.map { row: Row, _: RowMetadata ->
				LeaveMessage(
					from = row.get("from", String::class.java) ?: "",
					to = row.get("to", String::class.java) ?: "",
					message = row.get("message", String::class.java) ?: ""
				)
			}.asFlow().toList()
	}

	suspend fun insertMessage(from: String, to: String, message: String): Long {
		val connection: Connection = SQL.getConnection()

		return connection.createStatement(INSERT_MESSAGE_SQL)
			.bind("from", from)
			.bind("to", to)
			.bind("message", message)
			.execute()
			.awaitSingle()
			.rowsUpdated
			.awaitSingle()
	}

	suspend fun searchMessages(sender: String, receiver: String): List<LeaveMessage> {
		val connection: Connection = SQL.getConnection()

		return connection.createStatement(SEARCH_MESSAGE_SQL)
			.bind("from", sender)
			.bind("to", receiver)
			.execute()
			.awaitSingle()
			.map { row: Row, _: RowMetadata ->
				LeaveMessage(
					from = row.get("from", String::class.java) ?: "",
					to = row.get("to", String::class.java) ?: "",
					message = row.get("message", String::class.java) ?: ""
				)
			}
			.asFlow().toList()
	}



}

data class LeaveMessage(
	val from: String,
	val to: String,
	val message: String,
)
