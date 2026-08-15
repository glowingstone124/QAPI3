package org.qo.services.advancementServices

import io.r2dbc.spi.Row
import org.qo.datas.Enumerations
import org.qo.datas.ReactiveDatabase
import org.qo.orm.CardProfileOrm
import org.qo.orm.UserORM
import org.springframework.stereotype.Component

@Component
class AdvancementServiceImpl(
	private val cardProfileOrm: CardProfileOrm,
	private val database: ReactiveDatabase,
) {
	private val userORM = UserORM()

	data class Advancement(
		val id: Long,
		val name: String,
		val description: String,
	)

	enum class AddAdvancementResult {
		SUCCESS,
		ALREADY_EXISTS,
		FAILED,
		INVALID_PLAYER,
	}

	private val advancementOperations: Map<Enumerations.AdvancementsEnum, suspend (String) -> Boolean> = mapOf(
		Enumerations.AdvancementsEnum.ADVANCEMENT_PATCHOULI to { uuid: String ->
			cardProfileOrm.addCardToOwnedAsync(uuid, Enumerations.Card_PixelFantasia_Enum.PATCHOULI_LIB.id.toLong())
		},
		Enumerations.AdvancementsEnum.ADVANCEMENT_PROMETHUS to { uuid: String ->
			cardProfileOrm.addCardToOwnedAsync(uuid, Enumerations.Card_PixelFantasia_Enum.PROMETHUS.id.toLong())
		},
		Enumerations.AdvancementsEnum.ADVANCEMENT_KOISHI to { uuid: String ->
			cardProfileOrm.addCardToOwnedAsync(uuid, Enumerations.Card_PixelFantasia_Enum.KOISHI_NORZ.id.toLong())
		},
		Enumerations.AdvancementsEnum.ADVANCEMENT_ORIN to { uuid: String ->
			cardProfileOrm.addCardToOwnedAsync(uuid, Enumerations.Card_PixelFantasia_Enum.FUISLAND.id.toLong())
		},
		Enumerations.AdvancementsEnum.ADVANCEMENT_WHITE_JADE to { uuid: String ->
			cardProfileOrm.addCardToOwnedAsync(uuid, Enumerations.Card_PixelFantasia_Enum.CHERRY.id.toLong())
		},
	)

	suspend fun getCompleteAdvancements(username: String): List<Advancement> = database.all(
		"""
		SELECT a.id, a.name, a.description
		FROM advancement_completed ac
		JOIN advancements a ON ac.advancement_id = a.id
		WHERE ac.player_username = ?
		""".trimIndent(),
		listOf(username),
		::toAdvancement,
	)

	suspend fun getAchievementCompletePlayerCount(adv: Enumerations.AdvancementsEnum): Long =
		database.one(
			"SELECT COUNT(*) AS cnt FROM advancement_completed WHERE advancement_id = ?",
			listOf(adv.id),
		) { row -> row.get("cnt", java.lang.Long::class.java)?.toLong() ?: 0L } ?: 0L

	suspend fun addAdvancementCompletionSQL(
		adv: Enumerations.AdvancementsEnum,
		player: String,
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

		return try {
			if (database.one(checkPlayerSql, listOf(player)) { true } == null) {
				AddAdvancementResult.INVALID_PLAYER
			} else if (database.one(checkSql, listOf(player, adv.id)) { true } != null) {
				AddAdvancementResult.ALREADY_EXISTS
			} else if (database.execute(insertSql, listOf(player, adv.id)) > 0) {
				AddAdvancementResult.SUCCESS
			} else {
				AddAdvancementResult.FAILED
			}
		} catch (error: Exception) {
			error.printStackTrace()
			AddAdvancementResult.FAILED
		}
	}

	suspend fun addAdvancementCompletion(
		adv: Enumerations.AdvancementsEnum,
		player: String,
	): AddAdvancementResult {
		return try {
			database.inTransaction {
				val result = addAdvancementCompletionSQL(adv, player)
				if (result != AddAdvancementResult.SUCCESS) {
					return@inTransaction result
				}

				val operation = advancementOperations[adv] ?: return@inTransaction result
				val profileId = userORM.getProfileWithUserAsync(player)
				if (profileId.isBlank() || cardProfileOrm.readAsync(profileId) == null) {
					error("Card profile not found for player $player")
				}
				if (!operation(profileId)) {
					error("Failed to update card profile for player $player")
				}
				result
			}
		} catch (error: Exception) {
			error.printStackTrace()
			AddAdvancementResult.FAILED
		}
	}

	suspend fun getAllAdvancements(): List<Advancement> = database.all(
		"SELECT id, name, description FROM advancements",
		mapper = ::toAdvancement,
	)

	private fun toAdvancement(row: Row): Advancement = Advancement(
		id = row.get("id", java.lang.Long::class.java)!!.toLong(),
		name = row.get("name", String::class.java)!!,
		description = row.get("description", String::class.java)!!,
	)
}
