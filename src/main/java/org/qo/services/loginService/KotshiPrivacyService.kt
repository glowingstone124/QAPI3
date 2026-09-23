package org.qo.services.loginService

import kotlinx.coroutines.reactor.mono
import org.qo.datas.ReactiveDatabase
import org.qo.db.repository.KotshiPrivacyDbRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

data class KotshiPrivacySettings(
	val queryEnabled: Boolean,
)

/** Stores the account-level permission used by Kotshi's player lookup path. */
@Service
class KotshiPrivacyService @Autowired constructor(
	private val repository: KotshiPrivacyDbRepository,
) {
	constructor(database: ReactiveDatabase) : this(KotshiPrivacyDbRepository(database))

	suspend fun settings(username: String): KotshiPrivacySettings? = repository.settings(username)

	suspend fun isQueryEnabled(username: String): Boolean = settings(username)?.queryEnabled ?: false

	fun isQueryEnabledReactive(username: String): Mono<Boolean> = mono {
		isQueryEnabled(username)
	}

	suspend fun update(username: String, enabled: Boolean): KotshiPrivacySettings? =
		repository.update(username, enabled)
}
