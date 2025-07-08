package org.qo.services.loginService

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.qo.datas.Mapping
import org.qo.orm.CardOrm
import org.qo.orm.CardProfileOrm
import org.qo.orm.UserCardsOrm
import org.qo.orm.UserORM
import org.springframework.stereotype.Service

@Service
class PlayerCardCustomizationImpl(
	private val cardOrm: CardOrm,
	private val userCardsOrm: UserCardsOrm,
	private val cardProfileOrm: CardProfileOrm,
) {
	val userORM = UserORM()

	/**
	 * Return a player's owned cards
	 */
	fun getPlayerCardList(username: String): List<Mapping.UserCardRecord> {
		return userCardsOrm.getCardsByUsername(username)
	}

	/**
	 * Query a card with its id, return 'null' when it doesn't exist.
	 * @return Mapping.Cards?
	 */
	fun getCardInformation(id: Long): Mapping.Cards? {
		return cardOrm.read(id)
	}

	fun getAllCards(): List<Mapping.Cards> {
		return cardOrm.readAll()
	}

	fun getProfileDetailWithGivenName(name: String): Mapping.CardProfile? {
		val profileId = userORM.getProfileWithUser(name)
		return cardProfileOrm.read(profileId)
	}

	fun getProfileDetail(uuid: String): String? {
		var card = Mapping.CardProfile(
			uuid,
			1,
			0,
			0,
			0,
			"default"
		)
		if (!userORM.userWithProfileIDExists(uuid)) {
			return null
		}
		if (cardProfileOrm.read(uuid) == null) {
			cardProfileOrm.create(card)
			return Gson().toJson(card)
		}
		card = cardProfileOrm.read(uuid)!!
		val jsonArray = JsonArray().apply {
			add(JsonObject().apply {
				addProperty(
					getStatistic(card.statistic1!!, uuid).first,
					getStatistic(card.statistic1, uuid).second
				)
			})
			add(JsonObject().apply {
				addProperty(
					getStatistic(card.statistic2!!, uuid).first,
					getStatistic(card.statistic2, uuid).second
				)
			})
			add(JsonObject().apply {
				addProperty(
					getStatistic(card.statistic3!!, uuid).first,
					getStatistic(card.statistic3, uuid).second
				)
			})
		}
		val jsonObj = JsonObject().apply {
			addProperty("uuid", uuid)
			addProperty("cardId", card.cardId)
			add("statistic", jsonArray)
		}
		return jsonObj.toString()
	}

	private fun getStatistic(type: Int, token: String): Pair<String, String> {
		val user = userORM.read(userORM.getUserWithProfile(token))
		return when (type) {
			1 -> "Play time" to (user?.playtime?.toString() ?: "")
			else -> "" to ""
		}
	}
}