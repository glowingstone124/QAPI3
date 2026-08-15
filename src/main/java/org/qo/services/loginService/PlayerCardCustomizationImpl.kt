package org.qo.services.loginService

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.qo.datas.Mapping
import org.qo.datas.ReactiveDatabase
import org.qo.orm.CardOrm
import org.qo.orm.CardProfileOrm
import org.qo.orm.UserORM
import org.qo.orm.reactiveDatabase
import org.qo.orm.unsupportedSyncApi
import org.springframework.stereotype.Service
import kotlinx.coroutines.reactor.mono
import reactor.core.publisher.Mono

@Service
class PlayerCardCustomizationImpl(
	private val cardOrm: CardOrm,
	private val cardProfileOrm: CardProfileOrm,
	private val login: Login,
	private val authorityNeededServicesImpl: AuthorityNeededServicesImpl,
) {
	private var databaseOverride: ReactiveDatabase? = null
	private val statisticMapping = mapOf<Int, Pair<String, (Mapping.Users?) -> String>>(
		0 to (">_<" to { "" }),
		1 to ("Play time" to { it?.playtime?.toString() ?: "" }),
	)
	val userORM = UserORM()

	private val database: ReactiveDatabase
		get() = reactiveDatabase(databaseOverride)

	fun doesAvatarExist(avatarid: String): Boolean = unsupportedSyncApi("PlayerCardCustomizationImpl.doesAvatarExist")

	suspend fun doesAvatarExistAsync(avatarid: String): Boolean =
		database.one("SELECT url FROM avatars WHERE id = ?", listOf(avatarid)) { true } != null

	fun getPlayerCardList(username: String): List<Int> = unsupportedSyncApi("PlayerCardCustomizationImpl.getPlayerCardList")

	suspend fun getPlayerCardListAsync(username: String): List<Int> =
		cardProfileOrm.readAsync(userORM.getProfileWithUserAsync(username))
			?.owned
			?.split(",")
			?.mapNotNull { it.trim().toIntOrNull() }
			?: emptyList()

	fun getPlayerCardListAsJson(username: String): JsonArray =
		unsupportedSyncApi("PlayerCardCustomizationImpl.getPlayerCardListAsJson")

	suspend fun getPlayerCardListAsJsonAsync(username: String): JsonArray {
		val jsonArr = JsonArray()
		getPlayerCardListAsync(username).forEach {
			jsonArr.add(JsonObject().apply { addProperty("cardId", it) })
		}
		return jsonArr
	}

	suspend fun getAllAvatars(): List<Mapping.Avatar> = database.all("SELECT * FROM avatars") { row ->
		Mapping.Avatar(
			id = row.get("id", String::class.java).orEmpty(),
			url = row.get("url", String::class.java).orEmpty(),
		)
	}

	suspend fun updatePlayerAccountCardInfo(token: String, cardInfo: Mapping.CardProfile): Pair<Boolean, String> {
		val (accountName, errorCode) = login.validate(token)
		val precheckResult = authorityNeededServicesImpl.doPrecheck(accountName, errorCode)
		if (precheckResult != null) {
			return Pair(false, "User not exist!")
		}
		val profileDetailClazz = getProfileDetailWithGivenNameAsync(accountName!!)

		val modifiedCardId = cardInfo.cardId?.let { cardId ->
			getPlayerCardListAsync(accountName).find { it.toLong() == cardId }
		}?.toLong()

		val st1 = cardInfo.statistic1?.takeIf { statisticMapping.containsKey(it) }
		val st2 = cardInfo.statistic2?.takeIf { statisticMapping.containsKey(it) }
		val st3 = cardInfo.statistic3?.takeIf { statisticMapping.containsKey(it) }

		val avatar = if (doesAvatarExistAsync(cardInfo.avatar ?: "")) cardInfo.avatar else null

		val modifiedClazz = Mapping.CardProfile(
			cardId = modifiedCardId,
			statistic1 = st1,
			statistic2 = st2,
			statistic3 = st3,
			avatar = avatar,
			uuid = profileDetailClazz!!.uuid,
			owned = profileDetailClazz.owned,
		)
		cardProfileOrm.updateAsync(modifiedClazz)
		return Pair(true, "Updated Successfully")
	}

	fun getCardInformation(id: Long): Mapping.Cards? = unsupportedSyncApi("PlayerCardCustomizationImpl.getCardInformation")

	suspend fun getCardInformationAsync(id: Long): Mapping.Cards? = cardOrm.readAsync(id)

	fun getAllCards(): List<Mapping.Cards> = unsupportedSyncApi("PlayerCardCustomizationImpl.getAllCards")

	suspend fun getAllCardsAsync(): List<Mapping.Cards> = cardOrm.readAllAsync()

	fun getProfileDetailWithGivenName(name: String): Mapping.CardProfile? =
		unsupportedSyncApi("PlayerCardCustomizationImpl.getProfileDetailWithGivenName")

	suspend fun getProfileDetailWithGivenNameAsync(name: String): Mapping.CardProfile? {
		val profileId = userORM.getProfileWithUserAsync(name)
		return cardProfileOrm.readAsync(profileId)
	}

	fun getProfileDetailWithGivenNameReactive(name: String): Mono<Mapping.CardProfile> =
		mono { getProfileDetailWithGivenNameAsync(name) }

	fun getProfileDetail(uuid: String): String? = unsupportedSyncApi("PlayerCardCustomizationImpl.getProfileDetail")

	suspend fun getProfileDetailAsync(uuid: String): String? {
		var card = Mapping.CardProfile(
			uuid,
			1,
			0,
			0,
			0,
			"default",
			"1,2,3,4,5,6,7,8",
		)
		if (!userORM.userWithProfileIDExistsAsync(uuid)) {
			return null
		}
		if (cardProfileOrm.readAsync(uuid) == null) {
			cardProfileOrm.createAsync(card)
			return Gson().toJson(card)
		}
		card = cardProfileOrm.readAsync(uuid)!!
		val jsonArray = JsonArray().apply {
			add(JsonObject().apply {
				val statistic = getStatistic(card.statistic1 ?: 0, uuid)
				addProperty(statistic.first, statistic.second)
			})
			add(JsonObject().apply {
				val statistic = getStatistic(card.statistic2 ?: 0, uuid)
				addProperty(statistic.first, statistic.second)
			})
			add(JsonObject().apply {
				val statistic = getStatistic(card.statistic3 ?: 0, uuid)
				addProperty(statistic.first, statistic.second)
			})
		}
		return JsonObject().apply {
			addProperty("uuid", uuid)
			addProperty("cardId", card.cardId)
			add("statistic", jsonArray)
		}.toString()
	}

	private suspend fun getStatistic(type: Int, token: String): Pair<String, String> {
		val user = userORM.readAsync(userORM.getUserWithProfileAsync(token))
		return mapStatisticType(type, user)
	}

	private fun mapStatisticType(type: Int, user: Mapping.Users?): Pair<String, String> {
		val mapping = statisticMapping[type] ?: ("" to { _: Mapping.Users? -> "" })
		val (name, extractor) = mapping
		return name to extractor(user)
	}
}
