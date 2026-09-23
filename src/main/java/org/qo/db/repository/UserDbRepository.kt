package org.qo.db.repository

import org.qo.datas.Mapping
import org.qo.datas.ReactiveDatabase
import org.qo.orm.UserORM
import org.springframework.stereotype.Repository

@Repository
class UserDbRepository(
	private val database: ReactiveDatabase,
) {
	private val userORM = UserORM(database)

	suspend fun readAsync(username: String): Mapping.Users? = userORM.readAsync(username)

	suspend fun readAsync(uid: Long): Mapping.Users? = userORM.readAsync(uid)

	suspend fun createAsync(user: Mapping.Users): Long = userORM.createAsync(user)

	suspend fun updatePasswordAsync(uid: Long, password: String): Boolean =
		userORM.updatePasswordAsync(uid, password)

	suspend fun updateLastLoginByUsernameAsync(username: String, lastLogin: Long): Boolean =
		userORM.updateLastLoginByUsernameAsync(username, lastLogin)

	suspend fun updateLevelByUsernameAsync(username: String, level: Int): Boolean =
		userORM.updateLevelByUsernameAsync(username, level)

	suspend fun updateFrozenByUidAsync(uid: Long, frozen: Boolean): Boolean =
		userORM.updateFrozenByUidAsync(uid, frozen)

	suspend fun updateAsync(user: Mapping.Users): Boolean = userORM.updateAsync(user)

	suspend fun deleteAsync(input: Any): Boolean = userORM.deleteAsync(input)

	suspend fun countAsync(): Long = userORM.countAsync()

	suspend fun userWithProfileIDExistsAsync(uuid: String): Boolean =
		userORM.userWithProfileIDExistsAsync(uuid)

	suspend fun getProfileWithUserAsync(username: String): String =
		userORM.getProfileWithUserAsync(username)

	suspend fun getUserWithProfileAsync(uuid: String): String =
		userORM.getUserWithProfileAsync(uuid)

	suspend fun incrementPlaytimeAsync(username: String, delta: Int) {
		if (delta > 0) {
			database.execute(
				"UPDATE users SET playtime = COALESCE(playtime, 0) + ? WHERE username = ?",
				listOf(delta, username),
			)
			userORM.invalidateByUsername(username)
		}
	}

	suspend fun getPlaytimeAsync(username: String): Long? =
		database.one(
			"SELECT playtime FROM users WHERE username = ? LIMIT 1",
			listOf(username),
		) { row -> row.get("playtime", java.lang.Long::class.java)?.toLong() }

	suspend fun unfreezeUserAsync(uid: Long): Boolean {
		val result = database.execute(
			"UPDATE users SET frozen = false WHERE uid = ?",
			listOf(uid),
		) > 0
		userORM.read(uid)?.let { userORM.invalidateByUsername(it.username) }
		return result
	}
}
