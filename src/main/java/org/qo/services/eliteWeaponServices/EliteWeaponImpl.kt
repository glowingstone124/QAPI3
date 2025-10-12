package org.qo.services.eliteWeaponServices

import com.google.gson.Gson
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
}