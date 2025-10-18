package org.qo.services.eliteWeaponServices

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
	)

	fun handleEliteWeaponRequest(owner:String, type:String, description: String) : String?{
		if (db.hasEliteWeapon(owner,type)) {
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
				description
			)
		)
		return uuid
	}

	fun getEliteWeaponsFromUsername(username: String): String {
		db.queryAllEliteWeaponsFromUser(username).let {
			return gson.toJson(it)
		}
	}

	fun addEliteWeaponDMG(uuid: String, requester: String, amount: Int) {
		if (db.hasEliteWeapon(uuid, requester)) {
			db.addWeaponDamage(uuid, amount)
		}
	}
	fun addEliteWeaponKill(uuid: String, requester: String, amount: Int) {
		if (db.hasEliteWeapon(uuid, requester)) {
			db.addWeaponKills(uuid, amount)
		}
	}

	fun queryEliteUuid(uuid: String): String{
		db.getSpecfiedEliteWeaponByUuid(uuid)?.let {
			gson.toJson(it).asJsonObject().addProperty("find", true)
		}
		return JsonObject().addProperty("find", false).toString()
	}
}