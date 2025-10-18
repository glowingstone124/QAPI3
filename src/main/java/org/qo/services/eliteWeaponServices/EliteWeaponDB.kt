package org.qo.services.eliteWeaponServices

import jakarta.annotation.Resource
import org.qo.datas.ConnectionPool
import org.springframework.stereotype.Service

@Service
class EliteWeaponDB {
	val GET_ALL_ELITE_WEAPON_SQL = "SELECT * FROM elite_items WHERE owner = ?"
	val ADD_NEW_ELITE_WEAPON_SQL = "INSERT INTO elite_items(uuid, owner, type, damage, kills, description) VALUES (?,?,?,?,?,?)"

	fun addNewEliteWeapon(item: EliteWeaponImpl.EliteWeapon) {
		ConnectionPool.getConnection().prepareStatement(ADD_NEW_ELITE_WEAPON_SQL).use {
			it.setString(1, item.uuid)
			it.setString(2, item.owner)
			it.setString(3, item.type)
			it.setLong(4, 0L)
			it.setLong(5, 0L)
			it.setString(5, item.description)
			it.executeUpdate()
		}
	}

	fun queryAllEliteWeaponsFromUser(username: String): List<EliteWeaponImpl.EliteWeapon> {
		val list = mutableListOf<EliteWeaponImpl.EliteWeapon>()
		val connection = ConnectionPool.getConnection()

		connection.prepareStatement(GET_ALL_ELITE_WEAPON_SQL).use { stmt ->
			stmt.setString(1, username)

			stmt.executeQuery().use { rs ->
				while (rs.next()) {
					list.add(
						EliteWeaponImpl.EliteWeapon(
							rs.getString("uuid"),
							rs.getString("owner"),
							rs.getString("type"),
							rs.getLong("damage"),
							rs.getLong("kills"),
							rs.getString("description")
						)
					)
				}
			}
		}
		connection.close()
		return list
	}
	fun hasEliteWeapon(owner: String, type: String): Boolean {
		val sql = "SELECT 1 FROM elite_items WHERE owner = ? AND type = ? LIMIT 1"
		ConnectionPool.getConnection().use { conn ->
			conn.prepareStatement(sql).use { stmt ->
				stmt.setString(1, owner)
				stmt.setString(2, type)
				stmt.executeQuery().use { rs ->
					return rs.next()
				}
			}
		}
	}

	fun addWeaponDamage(uuid: String, dmg: Int) {
		val sql = "UPDATE elite_items SET damage = damage + ? WHERE uuid = ? LIMIT 1"
		ConnectionPool.getConnection().use { conn ->
			conn.prepareStatement(sql).use { stmt ->
				stmt.setInt(1, dmg)
				stmt.setString(2, uuid)
				stmt.executeUpdate()
			}
		}
	}
	fun addWeaponKills(uuid: String, kills: Int) {
		val sql = "UPDATE elite_items SET kills = kills + ? WHERE uuid = ? LIMIT 1"
		ConnectionPool.getConnection().use { conn ->
			conn.prepareStatement(sql).use { stmt ->
				stmt.setInt(1, kills)
				stmt.setString(2, uuid)
				stmt.executeUpdate()
			}
		}
	}

	fun getSpecfiedEliteWeaponByUuid(uuid: String): EliteWeaponImpl.EliteWeapon? {
		val sql = "SELECT * FROM elite_items WHERE uuid = ? LIMIT 1"
		ConnectionPool.getConnection().use { conn ->
			conn.prepareStatement(sql).use { stmt ->
				stmt.setString(1, uuid)
				stmt.executeQuery().use { rs ->
					while (rs.next()) {
						return EliteWeaponImpl.EliteWeapon(
							rs.getString("uuid"),
							rs.getString("owner"),
							rs.getString("type"),
							rs.getLong("damage"),
							rs.getLong("kills"),
							rs.getString("description")
						)
					}
				}
			}
		}
		return null
	}
}