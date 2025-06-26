package org.qo.services.loginService

import org.qo.datas.Mapping
import org.qo.orm.CardOrm
import org.qo.orm.CardProfileOrm
import org.qo.orm.UserCardsOrm
import org.qo.orm.UserORM
import org.springframework.stereotype.Service

@Service
class PlayerCardCustomizationImpl(private val cardOrm: CardOrm, private val userCardsOrm: UserCardsOrm, private val cardProfileOrm: CardProfileOrm) {
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

	fun getProfileDetail(uuid: String): Mapping.CardProfile? {
		val defaultCard= Mapping.CardProfile(
			uuid,
			1,
			0,
			0,
			0
		)
		if (!userORM.userWithProfileIDExists(uuid)) {
			return null
		}
		if (cardProfileOrm.read(uuid) == null) {
			cardProfileOrm.create(defaultCard)
			return defaultCard
		}
		return cardProfileOrm.read(uuid)
	}
}