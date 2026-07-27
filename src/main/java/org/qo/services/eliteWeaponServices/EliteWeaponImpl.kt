package org.qo.services.eliteWeaponServices

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.qo.services.gameStatusService.asJsonObject
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class EliteWeaponImpl(private val db: EliteWeaponDB) {

	val gson = Gson()

	data class EliteWeapon(
		val uuid:String,
		val owner:String,
		val type:String,
		val damage: Long,
		val kills: Long,
		val description: String,
		val name: String,
	)

	fun handleEliteWeaponRequest(owner:String, type:String, description: String, name: String) : String?{
		if (db.hasThisEliteWeaponType(owner,type)) {
			return null
		}
		val uuid = UUID.randomUUID().toString()
		db.addNewEliteWeapon(
			EliteWeapon(
				uuid,
				owner,
				type,
				0,
				0,
				description,
				name,
			)
		)
		return uuid
	}

	fun getEliteWeaponsFromUsername(username: String): String {
		db.queryAllEliteWeaponsFromUser(username).let {
			return gson.toJson(it)
		}
	}

	fun addEliteWeaponStats(uuid: String, requester: String, damage: Long, kills: Long): String {
		if (db.addWeaponStats(uuid, requester, damage, kills)) {
			return "ok->SQL execution"
		} else {
			return "err:user & uuid doesn't match"
		}
	}

	fun queryEliteUuid(uuid: String): String{
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
