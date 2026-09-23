package org.qo.utils

import com.google.gson.JsonObject
import kotlinx.coroutines.reactor.mono
import org.qo.datas.Mapping
import org.qo.datas.ReactiveDatabase
import org.qo.db.repository.LoginSecurityDbRepository
import org.qo.db.repository.UserDbRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

@Component
class UserProcessReactiveStore @Autowired constructor(
	private val userDbRepository: UserDbRepository,
	private val loginSecurityDbRepository: LoginSecurityDbRepository,
) {
	constructor(database: ReactiveDatabase) : this(
		UserDbRepository(database),
		LoginSecurityDbRepository(database),
	)

	fun readUser(username: String): Mono<Mapping.Users> = mono {
		userDbRepository.readAsync(username)
	}

	fun readUser(uid: Long): Mono<Mapping.Users> = mono {
		userDbRepository.readAsync(uid)
	}

	fun registerUser(user: Mapping.Users): Mono<String> = mono {
		when {
			userDbRepository.readAsync(user.username) != null -> "username_exists"
			userDbRepository.readAsync(user.uid) != null -> "uid_exists"
			userDbRepository.createAsync(user) > 0 -> "created"
			else -> "failed"
		}
	}

	fun updatePassword(uid: Long, password: String): Mono<Boolean> = mono {
		userDbRepository.updatePasswordAsync(uid, password)
	}

	fun updateLastLogin(username: String, lastLogin: Long): Mono<Boolean> = mono {
		userDbRepository.updateLastLoginByUsernameAsync(username, lastLogin)
	}

	fun updateLevel(username: String, level: Int): Mono<Boolean> = mono {
		userDbRepository.updateLevelByUsernameAsync(username, level)
	}

	fun incrementPlaytime(username: String, delta: Int): Mono<Void> = mono {
		userDbRepository.incrementPlaytimeAsync(username, delta)
	}.then()

	fun getTime(username: String): Mono<JsonObject> = mono {
		val playtime = userDbRepository.getPlaytimeAsync(username)
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
		userDbRepository.unfreezeUserAsync(uid)
	}

	fun getLatestLoginIP(username: String): Mono<String> = mono {
		loginSecurityDbRepository.getLatestLoginIp(username) ?: "error"
	}
}
