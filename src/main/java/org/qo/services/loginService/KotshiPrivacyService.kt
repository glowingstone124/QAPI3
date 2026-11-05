package org.qo.services.loginService

import kotlinx.coroutines.reactor.mono
import org.qo.datas.ReactiveDatabase
import org.qo.db.repository.KotshiPrivacyDbRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

data class KotshiPrivacySettings(
	val queryEnabled: Boolean,
	val activeResponseEnabled: Boolean = true,
)

/** Stores the account-level permission used by Kotshi's player lookup path and QBot active responses. */
@Service
class KotshiPrivacyService @Autowired constructor(
	private val repository: KotshiPrivacyDbRepository,
) {
	constructor(database: ReactiveDatabase) : this(KotshiPrivacyDbRepository(database))

	suspend fun settings(username: String): KotshiPrivacySettings? = repository.settings(username)

	suspend fun settingsByUid(uid: Long): KotshiPrivacySettings? = repository.settingsByUid(uid)

	suspend fun isQueryEnabled(username: String): Boolean = settings(username)?.queryEnabled ?: false

	suspend fun isActiveResponseEnabled(username: String): Boolean = settings(username)?.activeResponseEnabled ?: true

	suspend fun isActiveResponseEnabledByUid(uid: Long): Boolean = settingsByUid(uid)?.activeResponseEnabled ?: true

	fun isQueryEnabledReactive(username: String): Mono<Boolean> = mono {
		isQueryEnabled(username)
	}

	suspend fun update(username: String, enabled: Boolean): KotshiPrivacySettings? =
		repository.update(username, queryEnabled = enabled)

	suspend fun updateSettings(
		username: String,
		queryEnabled: Boolean? = null,
		activeResponseEnabled: Boolean? = null,
	): KotshiPrivacySettings? = repository.update(username, queryEnabled, activeResponseEnabled)

	suspend fun updateActiveResponseByUid(uid: Long, enabled: Boolean): Boolean =
		repository.updateByUid(uid, enabled)
}
