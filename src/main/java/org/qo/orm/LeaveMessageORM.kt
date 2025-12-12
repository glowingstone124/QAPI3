package org.qo.orm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.qo.datas.ConnectionPool
import java.sql.ResultSet
import java.sql.Statement

class LeaveMessageORM {
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

	private fun mapRow(rs: ResultSet): LeaveMessage {
		return LeaveMessage(
			from = rs.getString("from") ?: "",
			to = rs.getString("to") ?: "",
			message = rs.getString("message") ?: ""
		)
	}

	private suspend fun <T> executeQuery(sql: String, bindings: List<Any>, mapper: (ResultSet) -> T): List<T> =
		withContext(Dispatchers.IO) {
			val results = mutableListOf<T>()
			ConnectionPool.getConnection().use { connection ->
				connection.prepareStatement(sql).use { statement ->

					bindings.forEachIndexed { index, value ->
						statement.setObject(index + 1, value)
					}

					statement.executeQuery().use { rs ->
						while (rs.next()) {
							results.add(mapper(rs))
						}
					}
				}
			}
			results
		}


	suspend fun getDefinedSenderMessages(sender: String): List<LeaveMessage> {
		return executeQuery(
			sql = EXPLICIT_SENDER_QUERY_SQL,
			bindings = listOf(sender),
			mapper = ::mapRow
		)
	}

	suspend fun getDefinedReceiverMessages(receiver: String): List<LeaveMessage> {
		return executeQuery(
			sql = EXPLICIT_RECEIVER_QUERY_SQL,
			bindings = listOf(receiver),
			mapper = ::mapRow
		)
	}

	suspend fun searchMessages(sender: String, receiver: String): List<LeaveMessage> {
		return executeQuery(
			sql = SEARCH_MESSAGE_SQL,
			bindings = listOf(sender, receiver),
			mapper = ::mapRow
		)
	}

	suspend fun insertMessage(from: String, to: String, message: String): Long = withContext(Dispatchers.IO) {
		ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement(INSERT_MESSAGE_SQL, Statement.RETURN_GENERATED_KEYS).use { statement ->
				statement.setString(1, from)
				statement.setString(2, to)
				statement.setString(3, message)

				statement.executeUpdate()
				statement.generatedKeys.use { rs ->
					return@withContext if (rs.next()) {
						rs.getLong(1)
					} else {
						0L
					}
				}
			}
		}
	}

	suspend fun deleteSpecifiedSenderMessages(from: String, to: String, message: String) = withContext(Dispatchers.IO) {
		ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement(DELETE_SPECIFIED_MESSAGE_SQL).use { statement ->
				statement.setString(1, from)
				statement.setString(2, to)
				statement.setString(3, message)

				statement.executeUpdate()
			}
		}
	}
}
data class LeaveMessage(

	val from: String,

	val to: String,

	val message: String,

	)