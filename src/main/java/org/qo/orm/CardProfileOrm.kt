package org.qo.orm

import org.qo.datas.ConnectionPool
import org.qo.datas.Mapping
import org.springframework.stereotype.Service
import java.sql.ResultSet
import kotlin.use


@Service
class CardProfileOrm : CrudDao<Mapping.CardProfile> {
	companion object {
		private const val INSERT_PC_SQL =
			"INSERT INTO card_profile (uuid, cardId, statistic1,statistic2,statistic3,avatar,owned) VALUES (?, ?, ?, ?, ?,?,?)"
		private const val SELECT_PROFILE_BY_ID_SQL = "SELECT * FROM card_profile WHERE uuid = ?"
	}

	override fun create(item: Mapping.CardProfile): Long {
		ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement(INSERT_PC_SQL).use { stmt ->
				stmt.setString(1, item.uuid)
				stmt.setLong(2, item.cardId!!)
				stmt.setInt(3, item.statistic1!!)
				stmt.setInt(4, item.statistic2!!)
				stmt.setInt(5, item.statistic3!!)
				stmt.setString(6, item.avatar)
				stmt.setString(7, item.owned)
				stmt.executeUpdate()
			}
			return 0
		}
	}

	override fun read(input: Any): Mapping.CardProfile? {
		ConnectionPool.getConnection().use { connection ->
			val uuid = input as String
			connection.prepareStatement(SELECT_PROFILE_BY_ID_SQL).use { stmt ->
				stmt.setString(1, uuid)
				val rs = stmt.executeQuery()
				return if (rs.next()) parse(rs) else null
			}
		}
	}

	override fun update(item: Mapping.CardProfile): Boolean {
		ConnectionPool.getConnection().use { connection ->
			val updates = mutableListOf<String>()
			val params = mutableListOf<Any>()

			if (item.cardId != null) {
				updates.add("cardId = ?")
				params.add(item.cardId)
			}
			if (item.statistic1 != null) {
				updates.add("statistic1 = ?")
				params.add(item.statistic1)
			}
			if (item.statistic2 != null) {
				updates.add("statistic2 = ?")
				params.add(item.statistic2)
			}
			if (item.statistic3 != null) {
				updates.add("statistic3 = ?")
				params.add(item.statistic3)
			}
			if (item.avatar != null) {
				updates.add("avatar = ?")
				params.add(item.avatar)
			}

			if (updates.isEmpty()) return false

			val sql = "UPDATE card_profile SET ${updates.joinToString(", ")} WHERE uuid = ?"
			params.add(item.uuid) // uuid 最后加

			connection.prepareStatement(sql).use { stmt ->
				params.forEachIndexed { index, param ->
					stmt.setObject(index + 1, param)
				}
				val affectedRows = stmt.executeUpdate()
				return affectedRows > 0
			}
		}
	}



	override fun delete(input: Any): Boolean {
		ConnectionPool.getConnection().use { connection ->
			val sql = "DELETE FROM card_profile WHERE uuid = ?"
			connection.prepareStatement(sql).use { stmt ->
				stmt.setString(1, input as String)
				val affectedRows = stmt.executeUpdate()
				return affectedRows > 0
			}
		}
	}


	private fun parse(rs: ResultSet): Mapping.CardProfile {
		return Mapping.CardProfile(
			uuid = rs.getString("uuid"),
			cardId = rs.getLong("cardId"),
			statistic1 = rs.getInt("statistic1"),
			statistic2 = rs.getInt("statistic2"),
			statistic3 = rs.getInt("statistic3"),
			avatar = rs.getString("avatar"),
			owned = rs.getString("owned")
		)
	}
}