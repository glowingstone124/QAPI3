package org.qo.orm

import org.qo.datas.Mapping
import org.qo.datas.ReactiveDatabase
import org.springframework.stereotype.Service

@Service
class CardOrm : CrudDao<Mapping.Cards> {
	private var databaseOverride: ReactiveDatabase? = null

	constructor()

	constructor(database: ReactiveDatabase) : this() {
		this.databaseOverride = database
	}

	private val database: ReactiveDatabase
		get() = reactiveDatabase(databaseOverride)

	companion object {
		const val CREATE_CARD_SQL = "INSERT INTO cards (id, name, special, rarity, file_url) VALUES (?, ?, ?, ?, ?)"
		const val SEARCH_CARD_SQL = "SELECT * FROM cards WHERE id = ?"
		const val UPDATE_CARD_SQL = "UPDATE cards SET name = ?, special = ?, rarity = ?, file_url = ? WHERE id = ?"
		const val DELETE_CARD_SQL = "DELETE FROM cards WHERE id = ?"
		const val SEARCH_ALL_CARDS_SQL = "SELECT * FROM cards"
	}

	override fun create(item: Mapping.Cards): Long = unsupportedSyncApi("CardOrm.create")

	suspend fun createAsync(item: Mapping.Cards): Long =
		if (
			database.execute(
				CREATE_CARD_SQL,
				listOf(item.id, item.name, item.special, item.rarity.level, item.file_url),
			) == 1L
		) item.id else -1L

	override fun read(input: Any): Mapping.Cards? = unsupportedSyncApi("CardOrm.read")

	suspend fun readAsync(input: Long): Mapping.Cards? = database.one(
		SEARCH_CARD_SQL,
		listOf(input),
		::parseCard,
	)

	fun readAll(): List<Mapping.Cards> = unsupportedSyncApi("CardOrm.readAll")

	suspend fun readAllAsync(): List<Mapping.Cards> = database.all(
		SEARCH_ALL_CARDS_SQL,
		mapper = ::parseCard,
	)

	override fun update(item: Mapping.Cards): Boolean = unsupportedSyncApi("CardOrm.update")

	suspend fun updateAsync(item: Mapping.Cards): Boolean =
		database.execute(
			UPDATE_CARD_SQL,
			listOf(item.name, item.special, item.rarity.level, item.file_url, item.id),
		) > 0

	override fun delete(input: Any): Boolean = unsupportedSyncApi("CardOrm.delete")

	suspend fun deleteAsync(input: Long): Boolean =
		database.execute(DELETE_CARD_SQL, listOf(input)) > 0

	private fun parseCard(row: io.r2dbc.spi.Row): Mapping.Cards = Mapping.Cards(
		name = row.get("name", String::class.java).orEmpty(),
		id = longValue(row.get("id")) ?: 0L,
		special = row.get("special", String::class.java).orEmpty(),
		rarity = parseRarity(row.get("rarity")),
		file_url = row.get("file_url", String::class.java).orEmpty(),
	)

	private fun parseRarity(value: Any?): Mapping.CardsRarityEnum = when (value) {
		is String -> Mapping.CardsRarityEnum.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
			?: value.toIntOrNull()?.let { level -> Mapping.CardsRarityEnum.entries.firstOrNull { it.level == level } }
		is Number -> Mapping.CardsRarityEnum.entries.firstOrNull { it.level == value.toInt() }
		else -> null
	} ?: Mapping.CardsRarityEnum.COMMON
}
