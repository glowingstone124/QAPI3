package org.qo.services.loginService

import org.qo.datas.Mapping
import org.qo.orm.CardOrm
import org.qo.orm.UserCardsOrm
import org.springframework.stereotype.Service

@Service
class PlayerCardCustomizationImpl(private val cardOrm: CardOrm, private val userCardsOrm: UserCardsOrm) {
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
}