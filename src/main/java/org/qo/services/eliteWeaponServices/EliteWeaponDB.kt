package org.qo.services.eliteWeaponServices

import org.qo.datas.ReactiveDatabase
import org.springframework.stereotype.Service

@Service
class EliteWeaponDB(
	private val database: ReactiveDatabase,
) {
	private val getAllEliteWeaponSql = "SELECT * FROM elite_items WHERE owner = ?"
	private val addNewEliteWeaponSql = "INSERT INTO elite_items(uuid, owner, type, damage, kills, description, name) VALUES (?,?,?,?,?,?,?)"

	suspend fun addNewEliteWeapon(item: EliteWeaponImpl.EliteWeapon) {
		database.execute(
			addNewEliteWeaponSql,
			listOf(item.uuid, item.owner, item.type, 0L, 0L, item.description, item.name),
		)
	}

	suspend fun queryAllEliteWeaponsFromUser(username: String): List<EliteWeaponImpl.EliteWeapon> = database.all(
		getAllEliteWeaponSql,
		listOf(username),
		::toEliteWeapon,
	)

	suspend fun hasThisEliteWeaponType(owner: String, type: String): Boolean =
		database.one(
			"SELECT 1 FROM elite_items WHERE owner = ? AND type = ? LIMIT 1",
			listOf(owner, type),
		) { true } != null

	suspend fun addWeaponStats(uuid: String, requester: String, damage: Long, kills: Long): Boolean =
		database.execute(
			"UPDATE elite_items SET damage = damage + ?, kills = kills + ? WHERE uuid = ? AND owner = ?",
			listOf(damage, kills, uuid.trim(), requester.trim()),
		) > 0

	suspend fun getSpecfiedEliteWeaponByUuid(uuid: String): EliteWeaponImpl.EliteWeapon? = database.one(
		"SELECT * FROM elite_items WHERE uuid = ? LIMIT 1",
		listOf(uuid),
		::toEliteWeapon,
	)

	private fun toEliteWeapon(row: io.r2dbc.spi.Row): EliteWeaponImpl.EliteWeapon = EliteWeaponImpl.EliteWeapon(
		uuid = row.get("uuid", String::class.java)!!,
		owner = row.get("owner", String::class.java)!!,
		type = row.get("type", String::class.java)!!,
		damage = row.get("damage", java.lang.Long::class.java)?.toLong() ?: 0L,
		kills = row.get("kills", java.lang.Long::class.java)?.toLong() ?: 0L,
		description = row.get("description", String::class.java).orEmpty(),
		name = row.get("name", String::class.java).orEmpty(),
	)
}
