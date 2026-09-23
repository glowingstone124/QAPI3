package org.qo.services.eliteWeaponServices

import org.qo.datas.ReactiveDatabase
import org.qo.db.repository.EliteWeaponDbRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class EliteWeaponDB @Autowired constructor(
	private val repository: EliteWeaponDbRepository,
) {
	constructor(database: ReactiveDatabase) : this(EliteWeaponDbRepository(database))

	suspend fun addNewEliteWeapon(item: EliteWeaponImpl.EliteWeapon) = repository.addNewEliteWeapon(item)

	suspend fun queryAllEliteWeaponsFromUser(username: String): List<EliteWeaponImpl.EliteWeapon> =
		repository.queryAllEliteWeaponsFromUser(username)

	suspend fun hasThisEliteWeaponType(owner: String, type: String): Boolean =
		repository.hasThisEliteWeaponType(owner, type)

	suspend fun addWeaponStats(uuid: String, requester: String, damage: Long, kills: Long): Boolean =
		repository.addWeaponStats(uuid, requester, damage, kills)

	suspend fun getSpecfiedEliteWeaponByUuid(uuid: String): EliteWeaponImpl.EliteWeapon? =
		repository.getSpecfiedEliteWeaponByUuid(uuid)
}
