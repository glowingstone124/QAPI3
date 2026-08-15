package org.qo.services.eliteWeaponServices

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.qo.services.gameStatusService.asJsonObject
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class EliteWeaponImpl(private val db: EliteWeaponDB) {
	private val gson = Gson()

	data class EliteWeapon(
		val uuid: String,
		val owner: String,
		val type: String,
		val damage: Long,
		val kills: Long,
		val description: String,
		val name: String,
	)

	suspend fun handleEliteWeaponRequest(owner: String, type: String, description: String, name: String): String? {
		if (db.hasThisEliteWeaponType(owner, type)) {
			return null
		}
		val uuid = UUID.randomUUID().toString()
		db.addNewEliteWeapon(
			EliteWeapon(
				uuid = uuid,
				owner = owner,
				type = type,
				damage = 0,
				kills = 0,
				description = description,
				name = name,
			),
		)
		return uuid
	}

	suspend fun getEliteWeaponsFromUsername(username: String): String {
		return gson.toJson(db.queryAllEliteWeaponsFromUser(username))
	}

	suspend fun addEliteWeaponStats(uuid: String, requester: String, damage: Long, kills: Long): String {
		return if (db.addWeaponStats(uuid, requester, damage, kills)) {
			"ok->SQL execution"
		} else {
			"err:user & uuid doesn't match"
		}
	}

	suspend fun queryEliteUuid(uuid: String): String {
		db.getSpecfiedEliteWeaponByUuid(uuid)?.let {
			return gson.toJson(it).asJsonObject().apply {
				addProperty("find", true)
			}.toString()
		}
		return JsonObject().apply {
			addProperty("find", false)
		}.toString()
	}
}
