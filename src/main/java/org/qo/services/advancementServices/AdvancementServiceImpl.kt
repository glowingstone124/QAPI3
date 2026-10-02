package org.qo.services.advancementServices

import org.qo.datas.Enumerations.AdvancementsEnum
import org.qo.datas.Enumerations.Card_PixelFantasia_Enum
import org.qo.datas.ReactiveDatabase
import org.qo.db.repository.AdvancementDbRepository
import org.qo.orm.CardProfileOrm
import org.qo.orm.UserORM
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class AdvancementServiceImpl @Autowired constructor(
	private val cardProfileOrm: CardProfileOrm,
	private val advancementDbRepository: AdvancementDbRepository,
) {
	constructor(cardProfileOrm: CardProfileOrm, database: ReactiveDatabase) : this(
		cardProfileOrm,
		AdvancementDbRepository(database),
	)

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

	private val advancementCards = mapOf(
		AdvancementsEnum.ADVANCEMENT_PATCHOULI to Card_PixelFantasia_Enum.PATCHOULI_LIB,
		AdvancementsEnum.ADVANCEMENT_PROMETHUS to Card_PixelFantasia_Enum.PROMETHUS,
		AdvancementsEnum.ADVANCEMENT_KOISHI to Card_PixelFantasia_Enum.KOISHI_NORZ,
		AdvancementsEnum.ADVANCEMENT_ORIN to Card_PixelFantasia_Enum.FUISLAND,
		AdvancementsEnum.ADVANCEMENT_WHITE_JADE to Card_PixelFantasia_Enum.CHERRY,
	)

	suspend fun getCompleteAdvancements(username: String): List<Advancement> =
		advancementDbRepository.getCompleteAdvancements(username).map {
			Advancement(it.id, it.name, it.description)
		}

	suspend fun getAchievementCompletePlayerCount(adv: AdvancementsEnum): Long =
		advancementDbRepository.getAchievementCompletePlayerCount(adv.id.toLong())

	suspend fun addAdvancementCompletionSQL(
		adv: AdvancementsEnum,
		player: String,
	): AddAdvancementResult {
		return try {
			if (!advancementDbRepository.userExists(player)) {
				AddAdvancementResult.INVALID_PLAYER
			} else if (advancementDbRepository.hasCompleted(player, adv.id.toLong())) {
				AddAdvancementResult.ALREADY_EXISTS
			} else if (advancementDbRepository.insertCompletion(player, adv.id.toLong())) {
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
		adv: AdvancementsEnum,
		player: String,
	): AddAdvancementResult {
		return try {
			advancementDbRepository.inTransaction {
				val result = addAdvancementCompletionSQL(adv, player)
				if (result != AddAdvancementResult.SUCCESS) {
					return@inTransaction result
				}

				val card = advancementCards[adv] ?: return@inTransaction result
				val profileId = userORM.getProfileWithUserAsync(player)
				if (profileId.isBlank() || cardProfileOrm.readAsync(profileId) == null) {
					error("Card profile not found for player $player")
				}
				if (!cardProfileOrm.addCardToOwnedAsync(profileId, card.id.toLong())) {
					error("Failed to update card profile for player $player")
				}
				result
			}
		} catch (error: Exception) {
			error.printStackTrace()
			AddAdvancementResult.FAILED
		}
	}

	suspend fun getAllAdvancements(): List<Advancement> =
		advancementDbRepository.getAllAdvancements().map {
			Advancement(it.id, it.name, it.description)
		}
}
