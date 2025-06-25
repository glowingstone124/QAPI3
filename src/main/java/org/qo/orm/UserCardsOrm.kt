package org.qo.orm

import org.qo.datas.ConnectionPool
import org.qo.datas.Mapping
import org.springframework.stereotype.Service
import java.sql.Connection
import java.sql.ResultSet

@Service
class UserCardsOrm : CrudDao<Mapping.UserCardRecord> {

	private val connection: Connection = ConnectionPool.getConnection()

	override fun create(item: Mapping.UserCardRecord): Long {
		ConnectionPool.getConnection().use { connection ->
			val sql =
				"INSERT INTO user_cards (uid, username, card_id, obtained_time) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE username = VALUES(username), obtained_time = VALUES (obtained_time)"
			connection.prepareStatement(sql).use { stmt ->
				stmt.setLong(1, item.uid)
				stmt.setString(2, item.username)
				stmt.setLong(3, item.cardId)
				stmt.setLong(4, item.obtainedTimeStamp)
				stmt.executeUpdate()
			}
			return item.cardId
		}
	}

	override fun read(input: Any): Mapping.UserCardRecord? {
		ConnectionPool.getConnection().use { connection ->
			val (uid, cardId) = input as Pair<Long, Long>
			val sql = "SELECT * FROM user_cards WHERE uid = ? AND card_id = ?"
			connection.prepareStatement(sql).use { stmt ->
				stmt.setLong(1, uid)
				stmt.setLong(2, cardId)
				val rs = stmt.executeQuery()
				return if (rs.next()) parse(rs) else null
			}
		}
	}

	override fun update(item: Mapping.UserCardRecord): Boolean {
		ConnectionPool.getConnection().use { connection ->
			val sql = "UPDATE user_cards SET username = ?, obtained_time = ? WHERE uid = ? AND card_id = ?"
			connection.prepareStatement(sql).use { stmt ->
				stmt.setString(1, item.username)
				stmt.setLong(2, item.obtainedTimeStamp)
				stmt.setLong(3, item.uid)
				stmt.setLong(4, item.cardId)
				return stmt.executeUpdate() > 0
			}
		}
	}

	override fun delete(input: Any): Boolean {
		ConnectionPool.getConnection().use { connection ->
			val (uid, cardId) = input as Pair<Long, Long>
			val sql = "DELETE FROM user_cards WHERE uid = ? AND card_id = ?"
			connection.prepareStatement(sql).use { stmt ->
				stmt.setLong(1, uid)
				stmt.setLong(2, cardId)
				return stmt.executeUpdate() > 0
			}
		}
	}

	fun getCardsByUsername(username: String): List<Mapping.UserCardRecord> {
		ConnectionPool.getConnection().use { connection ->
			val sql = "SELECT * FROM user_cards WHERE username = ?"
			connection.prepareStatement(sql).use { stmt ->
				stmt.setString(1, username)
				val rs = stmt.executeQuery()
				val result = mutableListOf<Mapping.UserCardRecord>()
				while (rs.next()) {
					result.add(parse(rs))
				}
				return result
			}
		}
	}

	fun getUsersByCardId(cardId: Long): List<Mapping.UserCardRecord> {
		ConnectionPool.getConnection().use { connection ->
			val sql = "SELECT * FROM user_cards WHERE card_id = ?"
			connection.prepareStatement(sql).use { stmt ->
				stmt.setLong(1, cardId)
				val rs = stmt.executeQuery()
				val result = mutableListOf<Mapping.UserCardRecord>()
				while (rs.next()) {
					result.add(parse(rs))
				}
				return result
			}
		}
	}

	private fun parse(rs: ResultSet): Mapping.UserCardRecord {
		return Mapping.UserCardRecord(
			username = rs.getString("username"),
			uid = rs.getLong("uid"),
			cardId = rs.getLong("card_id"),
			obtainedTimeStamp = rs.getLong("obtained_time")
		)
	}
}
