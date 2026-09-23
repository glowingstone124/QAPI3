package org.qo.services.advancementServices

import org.qo.datas.Enumerations
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

	suspend fun getCompleteAdvancements(username: String): List<Advancement> =
		advancementDbRepository.getCompleteAdvancements(username).map {
			Advancement(it.id, it.name, it.description)
		}

	suspend fun getAchievementCompletePlayerCount(adv: Enumerations.AdvancementsEnum): Long =
		advancementDbRepository.getAchievementCompletePlayerCount(adv.id.toLong())

	suspend fun addAdvancementCompletionSQL(
		adv: Enumerations.AdvancementsEnum,
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
		adv: Enumerations.AdvancementsEnum,
		player: String,
	): AddAdvancementResult {
		return try {
			advancementDbRepository.inTransaction {
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

	suspend fun getAllAdvancements(): List<Advancement> =
		advancementDbRepository.getAllAdvancements().map {
			Advancement(it.id, it.name, it.description)
		}
}
