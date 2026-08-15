package org.qo.orm

import org.qo.datas.Mapping
import org.qo.datas.ReactiveDatabase
import org.springframework.stereotype.Service

@Service
class CardProfileOrm : CrudDao<Mapping.CardProfile> {
	private var databaseOverride: ReactiveDatabase? = null

	constructor()

	constructor(database: ReactiveDatabase) : this() {
		this.databaseOverride = database
	}

	private val database: ReactiveDatabase
		get() = reactiveDatabase(databaseOverride)

	companion object {
		private const val INSERT_PC_SQL =
			"INSERT INTO card_profile (uuid, cardId, statistic1, statistic2, statistic3, avatar, owned) VALUES (?, ?, ?, ?, ?, ?, ?)"
		private const val SELECT_PROFILE_BY_ID_SQL = "SELECT * FROM card_profile WHERE uuid = ?"
	}

	override fun create(item: Mapping.CardProfile): Long = unsupportedSyncApi("CardProfileOrm.create")

	suspend fun createAsync(item: Mapping.CardProfile): Long =
		if (
			database.execute(
				INSERT_PC_SQL,
				listOf(item.uuid, item.cardId, item.statistic1, item.statistic2, item.statistic3, item.avatar, item.owned),
			) == 1L
		) 1L else 0L

	override fun read(input: Any): Mapping.CardProfile? = unsupportedSyncApi("CardProfileOrm.read")

	suspend fun readAsync(uuid: String): Mapping.CardProfile? = database.one(
		SELECT_PROFILE_BY_ID_SQL,
		listOf(uuid),
		::parse,
	)

	override fun update(item: Mapping.CardProfile): Boolean = unsupportedSyncApi("CardProfileOrm.update")

	suspend fun updateAsync(item: Mapping.CardProfile): Boolean {
		val updates = mutableListOf<String>()
		val params = mutableListOf<Any?>()

		item.cardId?.let {
			updates += "cardId = ?"
			params += it
		}
		item.statistic1?.let {
			updates += "statistic1 = ?"
			params += it
		}
		item.statistic2?.let {
			updates += "statistic2 = ?"
			params += it
		}
		item.statistic3?.let {
			updates += "statistic3 = ?"
			params += it
		}
		item.avatar?.let {
			updates += "avatar = ?"
			params += it
		}
		item.owned?.let {
			updates += "owned = ?"
			params += it
		}
		if (updates.isEmpty()) return false

		return database.execute(
			"UPDATE card_profile SET ${updates.joinToString(", ")} WHERE uuid = ?",
			params + item.uuid,
		) > 0
	}

	override fun delete(input: Any): Boolean = unsupportedSyncApi("CardProfileOrm.delete")

	suspend fun deleteAsync(uuid: String): Boolean =
		database.execute("DELETE FROM card_profile WHERE uuid = ?", listOf(uuid)) > 0

	fun addCardToOwned(uuid: String, cardIdToAdd: Long): Boolean =
		unsupportedSyncApi("CardProfileOrm.addCardToOwned")

	suspend fun addCardToOwnedAsync(uuid: String, cardIdToAdd: Long): Boolean {
		val profile = readAsync(uuid) ?: return false
		val ownedSet = profile.owned
			?.split(",")
			?.mapNotNull { it.toLongOrNull() }
			?.toMutableSet()
			?: mutableSetOf()
		if (!ownedSet.add(cardIdToAdd)) return true
		profile.owned = ownedSet.joinToString(",")
		return updateAsync(profile)
	}

	private fun parse(row: io.r2dbc.spi.Row): Mapping.CardProfile = Mapping.CardProfile(
		uuid = row.get("uuid", String::class.java).orEmpty(),
		cardId = longValue(row.get("cardId")),
		statistic1 = intValue(row.get("statistic1")),
		statistic2 = intValue(row.get("statistic2")),
		statistic3 = intValue(row.get("statistic3")),
		avatar = row.get("avatar", String::class.java),
		owned = row.get("owned", String::class.java),
	)
}
