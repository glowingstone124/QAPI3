package org.qo.services.loginService

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.qo.datas.ConnectionPool
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
	private val login: Login,
	private val authorityNeededServicesImpl: AuthorityNeededServicesImpl
) {
	private val statisticMapping = mapOf<Int, Pair<String, (Mapping.Users?) -> String>>(
		0 to (">_<" to { "" }),
		1 to ("Play time" to { it?.playtime?.toString() ?: "" })
	)
	val userORM = UserORM()

	/**
	 * Return a player's owned cards
	 */

	fun doesAvatarExist(avatarid: String): Boolean {
		ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement("SELECT url FROM avatars WHERE id = ?").use { preparedStatement ->
				preparedStatement.setString(1, avatarid)
				preparedStatement.executeQuery().use { resultSet ->
					return resultSet.next()
				}
			}
		}
	}

	fun getPlayerCardList(username: String): List<Mapping.UserCardRecord> {
		return userCardsOrm.getCardsByUsername(username)
	}

	suspend fun updatePlayerAccountCardInfo(token: String, cardInfo: Mapping.CardProfile): Pair<Boolean, String> {
		val (accountName, errorCode) = login.validate(token)
		val precheckResult = authorityNeededServicesImpl.doPrecheck(accountName, errorCode)
		if (precheckResult != null) {
			return Pair(false, "User not exist!")
		}
		val profileDetailClazz = getProfileDetailWithGivenName(accountName!!)

		val modifiedCardId = cardInfo.cardId.let { cardId ->
			getPlayerCardList(accountName).let { cards ->
				cards.find {
					it.cardId == cardId
				}.let{
					return@let it?.cardId
				}
			}
		}

		val st1 = cardInfo.statistic1?.takeIf { statisticMapping.containsKey(it) }
		val st2 = cardInfo.statistic2?.takeIf { statisticMapping.containsKey(it) }
		val st3 = cardInfo.statistic3?.takeIf { statisticMapping.containsKey(it) }

		val avatar = if (doesAvatarExist(cardInfo.avatar ?: "")) cardInfo.avatar else null

		val modifiedClazz = Mapping.CardProfile(
			cardId = modifiedCardId,
			statistic1 = st1,
			statistic2 = st2,
			statistic3 = st3,
			avatar = avatar,
			uuid = profileDetailClazz!!.uuid
		)
		cardProfileOrm.update(modifiedClazz)
		return Pair(true, "Updated Successfully")
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
		return mapStatisticType(type, user)
	}
	private fun mapStatisticType(type: Int, user: Mapping.Users?): Pair<String, String> {
		val mapping = statisticMapping[type] ?: ("" to { _: Mapping.Users? -> "" })
		val (name, extractor) = mapping
		return name to extractor(user)
	}
}