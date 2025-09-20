package org.qo.services.advancementServices

import org.qo.datas.ConnectionPool
import org.qo.datas.Enumerations
import org.qo.orm.CardProfileOrm
import org.qo.orm.UserORM
import org.springframework.stereotype.Component
import kotlin.reflect.KFunction

@Component
class AdvancementServiceImpl(private val cardProfileOrm: CardProfileOrm) {

	val userORM = UserORM()

	data class Advancement(
		val id: Long,
		val name: String,
		val description: String
	)

	enum class AddAdvancementResult {
		SUCCESS,
		ALREADY_EXISTS,
		FAILED,
		INVALID_PLAYER
	}

	val advancementOperations: Map<Enumerations.AdvancementsEnum, (String) -> Unit> = mapOf(
		Enumerations.AdvancementsEnum.ADVANCEMENT_PATCHOULI to { uuid: String ->
			cardProfileOrm.addCardToOwned(uuid, Enumerations.Card_PixelFantasia_Enum.PATCHOULI_LIB.id.toLong())
		},
		Enumerations.AdvancementsEnum.ADVANCEMENT_PROMETHUS to { uuid: String ->
			cardProfileOrm.addCardToOwned(uuid, Enumerations.Card_PixelFantasia_Enum.PROMETHUS.id.toLong())
		},
		Enumerations.AdvancementsEnum.ADVANCEMENT_KOISHI to { uuid: String ->
			cardProfileOrm.addCardToOwned(uuid, Enumerations.Card_PixelFantasia_Enum.KOISHI_NORZ.id.toLong())
		},
		Enumerations.AdvancementsEnum.ADVANCEMENT_ORIN to { uuid: String ->
			cardProfileOrm.addCardToOwned(uuid, Enumerations.Card_PixelFantasia_Enum.FUISLAND.id.toLong())
		}
	)


	fun getCompleteAdvancements(username: String): List<Advancement> {
		val sql =
			"SELECT a.id, a.name, a.description FROM advancement_completed ac JOIN advancements a ON ac.advancement_id = a.id WHERE ac.player_username = ?"

		val result = mutableListOf<Advancement>()
		ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement(sql).use { stmt ->
				stmt.setString(1, username)
				stmt.executeQuery().use { rs ->
					while (rs.next()) {
						result.add(
							Advancement(
								rs.getLong("id"),
								rs.getString("name"),
								rs.getString("description")
							)
						)
					}
				}
			}
		}
		return result
	}

	fun getAchievementCompletePlayerCount(adv: Enumerations.AdvancementsEnum): Long {
		val sql = "SELECT COUNT(*) AS cnt FROM advancement_completed WHERE advancement_id = ?"
		ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement(sql).use { ps ->
				ps.setLong(1, adv.id)
				ps.executeQuery().use { rs ->
					return if (rs.next()) rs.getLong("cnt") else 0
				}
			}
		}
	}

	fun addAdvancementCompletionSQL(
		adv: Enumerations.AdvancementsEnum,
		player: String
	): AddAdvancementResult {
		val checkSql = """
        SELECT 1 
        FROM advancement_completed 
        WHERE player_username = ? AND advancement_id = ?
        LIMIT 1
    """.trimIndent()

		val insertSql = """
        INSERT INTO advancement_completed(player_username, advancement_id)
        VALUES (?, ?)
    """.trimIndent()
		val checkPlayerSql = "SELECT 1 FROM users WHERE username = ? LIMIT 1"

		ConnectionPool.getConnection().use { connection ->
			try {
				connection.prepareStatement(checkPlayerSql).use { ps ->
					ps.setString(1, player)
					ps.executeQuery().use { rs ->
						if (!rs.next()) return AddAdvancementResult.INVALID_PLAYER
					}
				}
				connection.prepareStatement(checkSql).use { ps ->
					ps.setString(1, player)
					ps.setLong(2, adv.id)
					ps.executeQuery().use { rs ->
						if (rs.next()) {
							return AddAdvancementResult.ALREADY_EXISTS
						}
					}
				}

				connection.prepareStatement(insertSql).use { ps ->
					ps.setString(1, player)
					ps.setLong(2, adv.id)
					val rows = ps.executeUpdate()
					return if (rows > 0) AddAdvancementResult.SUCCESS else AddAdvancementResult.FAILED
				}

			} catch (e: Exception) {
				e.printStackTrace()
				return AddAdvancementResult.FAILED
			}
		}
	}

	fun addAdvancementCompletion(
		adv: Enumerations.AdvancementsEnum,
		player: String
	): AddAdvancementResult {
		val result = addAdvancementCompletionSQL(adv, player)
		if (result == AdvancementServiceImpl.AddAdvancementResult.SUCCESS) {
			advancementOperations[adv]?.invoke(userORM.getProfileWithUser(player))
		}
		return result
	}

	fun getAllAdvancements(): List<Advancement> {
		val sql = "SELECT * FROM advancements"
		val result = mutableListOf<Advancement>()
		ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement(sql).use { stmt ->
				stmt.executeQuery().use { rs ->
					while (rs.next()) {
						result.add(
							Advancement(
								rs.getLong("id"),
								rs.getString("name"),
								rs.getString("description")
							)
						)
					}
				}
			}
		}
		return result
	}

}
