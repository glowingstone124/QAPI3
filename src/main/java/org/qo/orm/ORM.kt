package org.qo.orm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.qo.datas.Mapping.Users
import org.qo.datas.ConnectionPool
import java.util.concurrent.ConcurrentHashMap
import java.sql.PreparedStatement
import java.sql.ResultSet

class UserORM() : CrudDao<Users>  {

	private data class CachedUser(
		val user: Users,
		val expiresAt: Long
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
    fun count(): Long{
        val cachedValue = UserCountCache.getCachedValue()
        if (cachedValue != null) {
            return cachedValue
        }
	    val cnt: Long = ConnectionPool.getConnection().use { connection ->
		    connection.createStatement().use { statement ->
			    statement.executeQuery(COUNT_USERS_SQL).use { resultSet ->

				    if (resultSet.next()) {
					    resultSet.getLong("total")
				    } else {
					    0L
				    }
			    }
		    }
	    }

	    UserCountCache.updateCache(cnt)
	    return cnt
    }
    suspend fun countAsync(): Long = withContext(Dispatchers.IO) { count() }


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

    fun userWithProfileIDExists(uuid: String): Boolean {
        if (profileToNameCache.containsKey(uuid)) {
            return true
        }
        ConnectionPool.getConnection().use { connection ->
            connection.prepareStatement(SEARCH_USER_BY_PROFILE_UUID).use { statement ->
                statement.setString(1, uuid)
                statement.executeQuery().use { resultSet ->
                    val exists = resultSet.next()
                    if (exists) {
                        val username = resultSet.getString("username")
                        profileToNameCache[uuid] = username
                        nameToProfileCache[username] = uuid
                    }
                    return exists
                }
            }
        }
    }
    suspend fun userWithProfileIDExistsAsync(uuid: String): Boolean = withContext(Dispatchers.IO) {
        userWithProfileIDExists(uuid)
    }
    fun getProfileWithUser(username: String): String {
        getCachedByName(username)?.let { return it.profile_id }
        nameToProfileCache[username]?.let { return it }
        ConnectionPool.getConnection().use { connection ->
            connection.prepareStatement(SELECT_USER_BY_USERNAME_SQL).use { statement ->
                statement.setString(1, username)
                statement.executeQuery().use { resultSet ->
                    return if (resultSet.next()) {
                        val profileId = resultSet.getString("profile_id") ?: ""
                        if (profileId.isNotEmpty()) {
                            nameToProfileCache[username] = profileId
                            profileToNameCache[profileId] = username
                        }
                        profileId
                    } else {
                        ""
                    }
                }
            }
        }
    }
    suspend fun getProfileWithUserAsync(username: String): String = withContext(Dispatchers.IO) {
        getProfileWithUser(username)
    }
    fun getUserWithProfile(uuid: String): String {
        profileToNameCache[uuid]?.let { return it }
        ConnectionPool.getConnection().use { connection ->
            connection.prepareStatement(SELECT_USER_BY_UUID_SQL).use { statement ->
                statement.setString(1, uuid)
                statement.executeQuery().use { resultSet ->
                    return if (resultSet.next()) {
                        val username = resultSet.getString("username") ?: ""
                        if (username.isNotEmpty()) {
                            profileToNameCache[uuid] = username
                            nameToProfileCache[username] = uuid
                        }
                        username
                    } else {
                        ""
                    }
                }
            }
        }
    }
    suspend fun getUserWithProfileAsync(uuid: String): String = withContext(Dispatchers.IO) {
        getUserWithProfile(uuid)
    }


    override fun create(user: Users): Long = runBlocking {
        createAsync(user)
    }
    suspend fun createAsync(user: Users): Long = withContext(Dispatchers.IO) {
        val result = ConnectionPool.getConnection().use { connection ->
            connection.prepareStatement(INSERT_USER_SQL, PreparedStatement.RETURN_GENERATED_KEYS).use { stmt ->
                setStatementParams(stmt, user)
                stmt.executeUpdate()
                val keys = stmt.generatedKeys
                if (keys.next()) keys.getLong(1) else -1
            }
        }
	    invalidateUser(user.uid, user.username)
	    UserCountCache.invalidate()
	    return@withContext result
    }

    override fun read(input: Any): Users? = runBlocking {
        readAsync(input)
    }
    suspend fun readAsync(input: Any): Users? = withContext(Dispatchers.IO) {
        val (sql, paramSetter) = when (input) {
            is Long -> SELECT_USER_BY_ID_SQL to fun(stmt: PreparedStatement) { stmt.setLong(1, input) }
            is String -> SELECT_USER_BY_USERNAME_SQL to fun(stmt: PreparedStatement) { stmt.setString(1, input) }
            else -> throw IllegalArgumentException("Input must be either a String or a Long")
        }
	    when (input) {
		    is Long -> getCachedByUid(input)?.let { return@withContext it }
		    is String -> getCachedByName(input)?.let { return@withContext it }
	    }
        return@withContext ConnectionPool.getConnection().use { connection ->
            connection.prepareStatement(sql).use { stmt ->
                paramSetter(stmt)
                val rs = stmt.executeQuery()
                if (rs.next()) {
	                val user = mapResultSetToUser(rs)
	                cacheUser(user)
	                user
                } else null
            }
        }
    }
    fun updatePassword(uid: Long, newPassword: String): Boolean = runBlocking {
        updatePasswordAsync(uid, newPassword)
    }
    suspend fun updatePasswordAsync(uid: Long, newPassword: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val sql = "UPDATE users SET password = ? WHERE uid = ?"
            return@withContext ConnectionPool.getConnection().use { connection ->
                connection.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, newPassword)
                    stmt.setLong(2, uid)
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        } finally {
	        invalidateUser(uid, null)
        }
    }

	fun updateLevelByUsername(username: String, newLevel: Int): Boolean = runBlocking {
		updateLevelByUsernameAsync(username, newLevel)
	}
	suspend fun updateLevelByUsernameAsync(username: String, newLevel: Int): Boolean = withContext(Dispatchers.IO) {
		try {
			val sql = "UPDATE users SET exp_level = ? WHERE username = ?"
			return@withContext ConnectionPool.getConnection().use { connection ->
				connection.prepareStatement(sql).use { stmt ->
					stmt.setInt(1, newLevel)
					stmt.setString(2, username)
					stmt.executeUpdate() > 0
				}
			}
		} catch (e: Exception) {
			e.printStackTrace()
			return@withContext false
		} finally {
			invalidateUser(null, username)
		}
	}

	fun updateLastLoginByUsername(username: String, lastLogin: Long): Boolean = runBlocking {
		updateLastLoginByUsernameAsync(username, lastLogin)
	}
	suspend fun updateLastLoginByUsernameAsync(username: String, lastLogin: Long): Boolean = withContext(Dispatchers.IO) {
		try {
			val sql = "UPDATE users SET last_login = ? WHERE username = ?"
			return@withContext ConnectionPool.getConnection().use { connection ->
				connection.prepareStatement(sql).use { stmt ->
					stmt.setLong(1, lastLogin)
					stmt.setString(2, username)
					stmt.executeUpdate() > 0
				}
			}
		} catch (e: Exception) {
			e.printStackTrace()
			return@withContext false
		} finally {
			invalidateUser(null, username)
		}
	}

    override fun update(user: Users): Boolean = runBlocking {
        updateAsync(user)
    }
    suspend fun updateAsync(user: Users): Boolean = withContext(Dispatchers.IO) {
        val fields = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        user.username.let {
            fields.add("username = ?")
            values.add(it)
        }
        user.frozen?.let {
            fields.add("frozen = ?")
            values.add(it)
        }
        user.remain?.let {
            fields.add("remain = ?")
            values.add(it)
        }
        user.economy?.let {
            fields.add("economy = ?")
            values.add(it)
        }
        user.signed?.let {
            fields.add("signed = ?")
            values.add(it)
        }
        user.playtime?.let {
            fields.add("playtime = ?")
            values.add(it)
        }
        user.password.let {
            fields.add("password = ?")
            values.add(it)
        }
        user.temp?.let {
            fields.add("temp = ?")
            values.add(it)
        }
        user.invite?.let {
            fields.add("invite = ?")
            values.add(it)
        }
        user.exp_level?.let {
            fields.add("exp_level = ?")
            values.add(it)
        }
        user.score?.let {
            fields.add("score = ?")
            values.add(it)
        }
        user.damage?.let {
            fields.add("damage = ?")
            values.add(it)
        }
        user.last_login?.let {
            fields.add("last_login = ?")
            values.add(it)
        }
        if (fields.isEmpty()) return@withContext false
        val sql = "UPDATE users SET ${fields.joinToString(", ")} WHERE uid = ?"
        values.add(user.uid)
        val result = ConnectionPool.getConnection().use { connection ->
            connection.prepareStatement(sql).use { stmt ->
                setParamValues(stmt, values)
                stmt.executeUpdate() > 0
            }
        }
	    invalidateUser(user.uid, user.username)
	    return@withContext result
    }

    override fun delete(input: Any): Boolean = runBlocking {
        deleteAsync(input)
    }
    suspend fun deleteAsync(input: Any): Boolean = withContext(Dispatchers.IO) {
        val (sql, paramSetter) = when (input) {
            is Long -> DELETE_USER_BY_ID_SQL to { stmt: PreparedStatement -> stmt.setLong(1, input) }
            is String -> DELETE_USER_BY_USERNAME_SQL to { stmt: PreparedStatement -> stmt.setString(1, input) }
            else -> throw IllegalArgumentException("Input must be either a String or a Long")
        }
        val result = ConnectionPool.getConnection().use { connection ->
            connection.prepareStatement(sql).use { stmt ->
                paramSetter(stmt)
                stmt.executeUpdate() > 0
            }
        }
	    when (input) {
		    is Long -> invalidateUser(input, null)
		    is String -> invalidateUser(null, input)
	    }
	    if (result) {
		    UserCountCache.invalidate()
	    }
	    return@withContext result
    }

    private fun setStatementParams(stmt: PreparedStatement, user: Users) {
        stmt.setString(1, user.username)
        stmt.setLong(2, user.uid)
        stmt.setBoolean(3, user.frozen == true)
        stmt.setInt(4, user.remain ?: 3)
        stmt.setInt(5, user.economy ?: 0)
        stmt.setBoolean(6, user.signed == true)
        stmt.setInt(7, user.playtime ?: 0)
        stmt.setString(8, user.password)
        stmt.setBoolean(9, user.temp == true)
        stmt.setInt(10, user.invite ?: 0)
        stmt.setString(11, user.profile_id)
	    stmt.setInt(12, user.exp_level ?: 0)
	    stmt.setInt(13, user.score ?: 0)
	    stmt.setLong(14, user.last_login ?: 0L)
    }

    private fun mapResultSetToUser(rs: ResultSet): Users {
        return Users(
            username = rs.getString("username"),
            uid = rs.getLong("uid"),
            frozen = rs.getBoolean("frozen"),
            remain = rs.getInt("remain"),
            economy = rs.getInt("economy"),
            signed = rs.getBoolean("signed"),
            playtime = rs.getInt("playtime"),
            password = rs.getString("password"),
            temp = rs.getBoolean("temp"),
            invite = rs.getInt("invite"),
            profile_id = rs.getString("profile_id"),
			exp_level = rs.getInt("exp_level"),
			score = rs.getInt("score"),
			damage = rs.getLong("damage"),
			last_login = rs.getLong("last_login"),
        )
    }

    private fun setParamValues(stmt: PreparedStatement, values: MutableList<Any?>) {
        for ((index, value) in values.withIndex()) {
            when (value) {
                is String -> stmt.setString(index + 1, value)
                is Boolean -> stmt.setBoolean(index + 1, value)
                is Int -> stmt.setInt(index + 1, value)
                is Long -> stmt.setLong(index + 1, value)
                else -> throw IllegalArgumentException("Unsupported data type")
            }
        }
    }

}
