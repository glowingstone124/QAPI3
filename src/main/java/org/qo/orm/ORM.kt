package org.qo.orm

import io.r2dbc.spi.Row
import org.qo.datas.Mapping.Users
import org.qo.datas.ReactiveDatabase
import java.util.concurrent.ConcurrentHashMap

class UserORM() : CrudDao<Users> {
	private var databaseOverride: ReactiveDatabase? = null

	constructor(database: ReactiveDatabase) : this() {
		this.databaseOverride = database
	}

	private val database: ReactiveDatabase
		get() = reactiveDatabase(databaseOverride)

	private data class CachedUser(
		val user: Users,
		val expiresAt: Long,
	)

	private fun getCachedByUid(uid: Long): Users? {
		val cached = userByUidCache[uid] ?: return null
		if (System.currentTimeMillis() >= cached.expiresAt) {
			invalidateUser(uid, cached.user.username)
			return null
		}
		return cached.user
	}

	private fun getCachedByName(username: String): Users? {
		val cached = userByNameCache[username] ?: return null
		if (System.currentTimeMillis() >= cached.expiresAt) {
			invalidateUser(cached.user.uid, username)
			return null
		}
		return cached.user
	}

	private fun cacheUser(user: Users) {
		trimUserCacheIfNeeded()
		val expiresAt = System.currentTimeMillis() + userCacheTtlMs
		val cached = CachedUser(user, expiresAt)
		userByUidCache[user.uid] = cached
		userByNameCache[user.username] = cached
		uidToNameCache[user.uid] = user.username
		nameToUidCache[user.username] = user.uid
		profileToNameCache[user.profile_id] = user.username
		nameToProfileCache[user.username] = user.profile_id
	}

	private fun invalidateUser(uid: Long?, username: String?) {
		if (uid != null) {
			userByUidCache.remove(uid)
			uidToNameCache.remove(uid)?.let { cachedName ->
				userByNameCache.remove(cachedName)
				nameToUidCache.remove(cachedName)
				nameToProfileCache.remove(cachedName)?.let { cachedProfile ->
					profileToNameCache.remove(cachedProfile)
				}
			}
		}
		if (!username.isNullOrBlank()) {
			userByNameCache.remove(username)
			nameToUidCache.remove(username)?.let { cachedUid ->
				userByUidCache.remove(cachedUid)
				uidToNameCache.remove(cachedUid)
			}
			nameToProfileCache.remove(username)?.let { cachedProfile ->
				profileToNameCache.remove(cachedProfile)
			}
		}
	}

	fun count(): Long = UserCountCache.getCachedValue() ?: unsupportedSyncApi("UserORM.count")

	suspend fun countAsync(): Long {
		UserCountCache.getCachedValue()?.let { return it }
		val count = database.one(COUNT_USERS_SQL) { row ->
			longValue(row.get("total")) ?: 0L
		} ?: 0L
		UserCountCache.updateCache(count)
		return count
	}

	companion object {
		private object UserCountCache {
			private var cachedValue: Long? = null
			private var lastUpdated: Long = 0
			private const val CACHE_EXPIRATION_TIME = 5 * 60 * 1000L

			fun getCachedValue(): Long? {
				val currentTime = System.currentTimeMillis()
				if (cachedValue != null && currentTime - lastUpdated < CACHE_EXPIRATION_TIME) {
					return cachedValue
				}
				return null
			}

			fun updateCache(value: Long) {
				cachedValue = value
				lastUpdated = System.currentTimeMillis()
			}

			fun invalidate() {
				cachedValue = null
				lastUpdated = 0
			}
		}

		private val userByUidCache = ConcurrentHashMap<Long, CachedUser>()
		private val userByNameCache = ConcurrentHashMap<String, CachedUser>()
		private val uidToNameCache = ConcurrentHashMap<Long, String>()
		private val nameToUidCache = ConcurrentHashMap<String, Long>()
		private val profileToNameCache = ConcurrentHashMap<String, String>()
		private val nameToProfileCache = ConcurrentHashMap<String, String>()
		private const val userCacheTtlMs = 5 * 60 * 1000L
		private const val maxUserCacheEntries = 10_000

		private const val INSERT_USER_SQL =
			"INSERT INTO users (username, uid, frozen, remain, economy, signed, playtime, password, temp, invite, profile_id, exp_level, score, last_login) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
		private const val SELECT_USER_BY_ID_SQL = "SELECT * FROM users WHERE uid = ?"
		private const val SELECT_USER_BY_USERNAME_SQL = "SELECT * FROM users WHERE username = ?"
		private const val SELECT_USER_BY_UUID_SQL = "SELECT * FROM users WHERE profile_id = ?"
		private const val DELETE_USER_BY_ID_SQL = "DELETE FROM users WHERE uid = ?"
		private const val DELETE_USER_BY_USERNAME_SQL = "DELETE FROM users WHERE username = ?"
		private const val SEARCH_USER_BY_PROFILE_UUID = "SELECT username FROM users WHERE profile_id = ? LIMIT 1"
		private const val COUNT_USERS_SQL = "SELECT COUNT(*) AS total FROM users"

		private fun trimUserCacheIfNeeded() {
			if (userByUidCache.size <= maxUserCacheEntries) return

			val currentTime = System.currentTimeMillis()
			userByUidCache.forEach { (uid, cached) ->
				if (currentTime >= cached.expiresAt) {
					userByUidCache.remove(uid)
					userByNameCache.remove(cached.user.username)
					uidToNameCache.remove(uid)
					nameToUidCache.remove(cached.user.username)
					profileToNameCache.remove(cached.user.profile_id)
					nameToProfileCache.remove(cached.user.username)
				}
			}

			if (userByUidCache.size <= maxUserCacheEntries) return

			val overflow = userByUidCache.size - maxUserCacheEntries
			userByUidCache.entries
				.sortedBy { it.value.expiresAt }
				.take(overflow)
				.forEach { (uid, cached) ->
					userByUidCache.remove(uid)
					userByNameCache.remove(cached.user.username)
					uidToNameCache.remove(uid)
					nameToUidCache.remove(cached.user.username)
					profileToNameCache.remove(cached.user.profile_id)
					nameToProfileCache.remove(cached.user.username)
				}
		}
	}

	fun userWithProfileIDExists(uuid: String): Boolean =
		profileToNameCache.containsKey(uuid) || unsupportedSyncApi("UserORM.userWithProfileIDExists")

	suspend fun userWithProfileIDExistsAsync(uuid: String): Boolean {
		if (profileToNameCache.containsKey(uuid)) {
			return true
		}
		val username = database.one(
			SEARCH_USER_BY_PROFILE_UUID,
			listOf(uuid),
		) { row ->
			row.get("username", String::class.java).orEmpty()
		}?.takeIf { it.isNotEmpty() }
		if (username != null) {
			profileToNameCache[uuid] = username
			nameToProfileCache[username] = uuid
			return true
		}
		return false
	}

	fun getProfileWithUser(username: String): String =
		getCachedByName(username)?.profile_id
			?: nameToProfileCache[username]
			?: unsupportedSyncApi("UserORM.getProfileWithUser")

	suspend fun getProfileWithUserAsync(username: String): String {
		getCachedByName(username)?.let { return it.profile_id }
		nameToProfileCache[username]?.let { return it }
		return database.one(
			SELECT_USER_BY_USERNAME_SQL,
			listOf(username),
		) { row ->
			row.get("profile_id", String::class.java).orEmpty()
		}?.also { profileId ->
			if (profileId.isNotEmpty()) {
				nameToProfileCache[username] = profileId
				profileToNameCache[profileId] = username
			}
		}.orEmpty()
	}

	fun getUserWithProfile(uuid: String): String =
		profileToNameCache[uuid] ?: unsupportedSyncApi("UserORM.getUserWithProfile")

	suspend fun getUserWithProfileAsync(uuid: String): String {
		profileToNameCache[uuid]?.let { return it }
		return database.one(
			SELECT_USER_BY_UUID_SQL,
			listOf(uuid),
		) { row ->
			row.get("username", String::class.java).orEmpty()
		}?.also { username ->
			if (username.isNotEmpty()) {
				profileToNameCache[uuid] = username
				nameToProfileCache[username] = uuid
			}
		}.orEmpty()
	}

	override fun create(user: Users): Long = unsupportedSyncApi("UserORM.create")

	suspend fun createAsync(user: Users): Long {
		val result = database.execute(
			INSERT_USER_SQL,
			listOf(
				user.username,
				user.uid,
				user.frozen == true,
				user.remain ?: 3,
				user.economy ?: 0,
				user.signed == true,
				user.playtime ?: 0,
				user.password,
				user.temp == true,
				user.invite ?: 0,
				user.profile_id,
				user.exp_level ?: 0,
				user.score ?: 0,
				user.last_login ?: 0L,
			),
		)
		invalidateUser(user.uid, user.username)
		UserCountCache.invalidate()
		return if (result == 1L) user.uid else -1L
	}

	override fun read(input: Any): Users? = when (input) {
		is Long -> getCachedByUid(input)
		is String -> getCachedByName(input)
		else -> unsupportedSyncApi("UserORM.read")
	}

	suspend fun readAsync(input: Any): Users? {
		val (sql, bindings) = when (input) {
			is Long -> {
				getCachedByUid(input)?.let { return it }
				SELECT_USER_BY_ID_SQL to listOf(input)
			}
			is String -> {
				getCachedByName(input)?.let { return it }
				SELECT_USER_BY_USERNAME_SQL to listOf(input)
			}
			else -> throw IllegalArgumentException("Input must be either a String or a Long")
		}
		return database.one(sql, bindings, ::mapRowToUser)?.also(::cacheUser)
	}

	fun updatePassword(uid: Long, newPassword: String): Boolean = unsupportedSyncApi("UserORM.updatePassword")

	suspend fun updatePasswordAsync(uid: Long, newPassword: String): Boolean = try {
		database.execute(
			"UPDATE users SET password = ? WHERE uid = ?",
			listOf(newPassword, uid),
		) > 0
	} finally {
		invalidateUser(uid, null)
	}

	fun invalidateByUsername(username: String) {
		invalidateUser(null, username)
	}

	fun updateFrozenByUid(uid: Long, frozen: Boolean): Boolean = unsupportedSyncApi("UserORM.updateFrozenByUid")

	suspend fun updateFrozenByUidAsync(uid: Long, frozen: Boolean): Boolean = try {
		database.execute(
			"UPDATE users SET frozen = ? WHERE uid = ?",
			listOf(frozen, uid),
		) > 0
	} finally {
		invalidateUser(uid, null)
	}

	fun updateLevelByUsername(username: String, newLevel: Int): Boolean =
		unsupportedSyncApi("UserORM.updateLevelByUsername")

	suspend fun updateLevelByUsernameAsync(username: String, newLevel: Int): Boolean = try {
		database.execute(
			"UPDATE users SET exp_level = ? WHERE username = ?",
			listOf(newLevel, username),
		) > 0
	} finally {
		invalidateUser(null, username)
	}

	fun updateLastLoginByUsername(username: String, lastLogin: Long): Boolean =
		unsupportedSyncApi("UserORM.updateLastLoginByUsername")

	suspend fun updateLastLoginByUsernameAsync(username: String, lastLogin: Long): Boolean = try {
		database.execute(
			"UPDATE users SET last_login = ? WHERE username = ?",
			listOf(lastLogin, username),
		) > 0
	} finally {
		invalidateUser(null, username)
	}

	override fun update(user: Users): Boolean = unsupportedSyncApi("UserORM.update")

	suspend fun updateAsync(user: Users): Boolean {
		val fields = mutableListOf<String>()
		val values = mutableListOf<Any?>()
		fields += "username = ?"
		values += user.username
		user.frozen?.let {
			fields += "frozen = ?"
			values += it
		}
		user.remain?.let {
			fields += "remain = ?"
			values += it
		}
		user.economy?.let {
			fields += "economy = ?"
			values += it
		}
		user.signed?.let {
			fields += "signed = ?"
			values += it
		}
		user.playtime?.let {
			fields += "playtime = ?"
			values += it
		}
		fields += "password = ?"
		values += user.password
		user.temp?.let {
			fields += "temp = ?"
			values += it
		}
		user.invite?.let {
			fields += "invite = ?"
			values += it
		}
		user.exp_level?.let {
			fields += "exp_level = ?"
			values += it
		}
		user.score?.let {
			fields += "score = ?"
			values += it
		}
		user.damage?.let {
			fields += "damage = ?"
			values += it
		}
		user.last_login?.let {
			fields += "last_login = ?"
			values += it
		}
		if (fields.isEmpty()) return false
		val result = database.execute(
			"UPDATE users SET ${fields.joinToString(", ")} WHERE uid = ?",
			values + user.uid,
		) > 0
		invalidateUser(user.uid, user.username)
		return result
	}

	override fun delete(input: Any): Boolean = unsupportedSyncApi("UserORM.delete")

	suspend fun deleteAsync(input: Any): Boolean {
		val (sql, bindings) = when (input) {
			is Long -> DELETE_USER_BY_ID_SQL to listOf(input)
			is String -> DELETE_USER_BY_USERNAME_SQL to listOf(input)
			else -> throw IllegalArgumentException("Input must be either a String or a Long")
		}
		val result = database.execute(sql, bindings) > 0
		when (input) {
			is Long -> invalidateUser(input, null)
			is String -> invalidateUser(null, input)
		}
		if (result) {
			UserCountCache.invalidate()
		}
		return result
	}

	private fun mapRowToUser(row: Row): Users = Users(
		username = row.get("username", String::class.java).orEmpty(),
		uid = longValue(row.get("uid")) ?: 0L,
		frozen = booleanValue(row.get("frozen")) ?: false,
		remain = intValue(row.get("remain")) ?: 0,
		economy = intValue(row.get("economy")) ?: 0,
		signed = booleanValue(row.get("signed")) ?: false,
		playtime = intValue(row.get("playtime")) ?: 0,
		password = row.get("password", String::class.java).orEmpty(),
		temp = booleanValue(row.get("temp")) ?: false,
		invite = intValue(row.get("invite")) ?: 0,
		profile_id = row.get("profile_id", String::class.java).orEmpty(),
		exp_level = intValue(row.get("exp_level")) ?: 0,
		score = intValue(row.get("score")) ?: 0,
		damage = longValue(row.get("damage")) ?: 0L,
		last_login = longValue(row.get("last_login")),
	)
}
