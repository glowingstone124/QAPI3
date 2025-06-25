package org.qo.orm

import org.qo.datas.ConnectionPool
import org.qo.datas.Mapping
import org.springframework.stereotype.Service
import java.sql.Connection
import java.sql.ResultSet

@Service
class CardOrm : CrudDao<Mapping.Cards> {


	companion object {
		const val CREATE_CARD_SQL = "INSERT INTO cards (id, name, special, rarity, file_url) VALUES (?, ?, ?, ?, ?)"
		const val SEARCH_CARD_SQL = "SELECT * FROM cards WHERE id = ?"
		const val UPDATE_CARD_SQL = "UPDATE cards SET name = ?, special = ?, rarity = ? WHERE id = ?"
		const val DELETE_CARD_SQL = "DELETE FROM cards WHERE id = ?"
		const val SEARCH_ALL_CARDS_SQL = "SELECT * FROM cards"
	}

	override fun create(item: Mapping.Cards): Long {
		ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement(CREATE_CARD_SQL).use { stmt ->
				stmt.setLong(1, item.id)
				stmt.setString(2, item.name)
				stmt.setString(3, item.special)
				stmt.setString(4, item.rarity.name)
				stmt.setString(5, item.file_url)
				stmt.executeUpdate()
			}
		}
		return item.id
	}

	override fun read(input: Any): Mapping.Cards? {
		ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement(SEARCH_CARD_SQL).use { stmt ->
				stmt.setLong(1, input as Long)
				val rs = stmt.executeQuery()
				return if (rs.next()) parseCard(rs) else null
			}
		}
	}

	fun readAll(): List<Mapping.Cards> {
		ConnectionPool.getConnection().use { connection ->
			val cardList = mutableListOf<Mapping.Cards>()
			connection.prepareStatement(SEARCH_ALL_CARDS_SQL).use { stmt ->
				val rs = stmt.executeQuery()
				while (rs.next()) {
					cardList.add(parseCard(rs))
				}
			}
			return cardList
		}
	}

	override fun update(item: Mapping.Cards): Boolean {
		ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement(UPDATE_CARD_SQL).use { stmt ->
				stmt.setString(1, item.name)
				stmt.setString(2, item.special)
				stmt.setString(3, item.rarity.name)
				stmt.setLong(4, item.id)
				return stmt.executeUpdate() > 0
			}
		}
	}

	override fun delete(input: Any): Boolean {
		ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement(DELETE_CARD_SQL).use { stmt ->
				stmt.setLong(1, input as Long)
				return stmt.executeUpdate() > 0
			}
		}
	}

	private fun parseCard(rs: ResultSet): Mapping.Cards {
		return Mapping.Cards(
			name = rs.getString("name"),
			id = rs.getLong("id"),
			special = rs.getString("special"),
			rarity = Mapping.CardsRarityEnum.entries.find {
				it.level == rs.getInt("rarity")
			}!!,
			file_url = rs.getString("file_url"),
		)
	}
}
