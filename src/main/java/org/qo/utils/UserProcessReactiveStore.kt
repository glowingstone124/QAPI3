package org.qo.utils

import com.google.gson.JsonObject
import kotlinx.coroutines.reactor.mono
import org.qo.datas.ReactiveDatabase
import org.qo.datas.Mapping
import org.qo.orm.UserORM
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

@Component
class UserProcessReactiveStore(
	private val database: ReactiveDatabase,
) {
	private val userORM = UserORM()

	fun readUser(username: String): Mono<Mapping.Users> = mono {
		userORM.readAsync(username)
	}

	fun readUser(uid: Long): Mono<Mapping.Users> = mono {
		userORM.readAsync(uid)
	}

	fun registerUser(user: Mapping.Users): Mono<String> = mono {
		when {
			userORM.readAsync(user.username) != null -> "username_exists"
			userORM.readAsync(user.uid) != null -> "uid_exists"
			userORM.createAsync(user) > 0 -> "created"
			else -> "failed"
		}
	}

	fun updatePassword(uid: Long, password: String): Mono<Boolean> = mono {
		userORM.updatePasswordAsync(uid, password)
	}

	fun updateLastLogin(username: String, lastLogin: Long): Mono<Boolean> = mono {
		userORM.updateLastLoginByUsernameAsync(username, lastLogin)
	}

	fun updateLevel(username: String, level: Int): Mono<Boolean> = mono {
		userORM.updateLevelByUsernameAsync(username, level)
	}

	fun incrementPlaytime(username: String, delta: Int): Mono<Void> = mono {
		if (delta > 0) {
			database.execute(
				"UPDATE users SET playtime = COALESCE(playtime, 0) + ? WHERE username = ?",
				listOf(delta, username),
			)
		}
	}.then()

	fun getTime(username: String): Mono<JsonObject> = mono {
		val playtime = database.one(
			"SELECT playtime FROM users WHERE username = ? LIMIT 1",
			listOf(username),
		) { row -> row.get("playtime", java.lang.Long::class.java)?.toLong() }
		JsonObject().apply {
			if (playtime != null) {
				addProperty("name", username)
				addProperty("time", playtime)
			} else {
				addProperty("error", -1)
			}
		}
	}

	fun unfreezeUser(uid: Long): Mono<Boolean> = mono {
		database.execute(
			"UPDATE users SET frozen = false WHERE uid = ?",
			listOf(uid),
		) > 0
	}

	fun getLatestLoginIP(username: String): Mono<String> = mono {
		database.one(
			"SELECT ip FROM loginip WHERE username = ? LIMIT 1",
			listOf(username),
		) { row -> row.get("ip", String::class.java) } ?: "error"
	}
}
