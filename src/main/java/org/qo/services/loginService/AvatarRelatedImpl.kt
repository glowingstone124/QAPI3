package org.qo.services.loginService

import org.qo.datas.ReactiveDatabase
import org.qo.orm.reactiveDatabase
import org.qo.orm.unsupportedSyncApi
import org.springframework.stereotype.Service
import kotlinx.coroutines.reactor.mono
import reactor.core.publisher.Mono

@Service
class AvatarRelatedImpl {
	private var databaseOverride: ReactiveDatabase? = null

	constructor()

	constructor(database: ReactiveDatabase) : this() {
		this.databaseOverride = database
	}

	private val database: ReactiveDatabase
		get() = reactiveDatabase(databaseOverride)

	fun getAvatarUrl(id: String): String? = unsupportedSyncApi("AvatarRelatedImpl.getAvatarUrl")

	suspend fun getAvatarUrlAsync(id: String): String? = database.one(
		"SELECT url FROM avatars WHERE id = ?",
		listOf(id),
	) { row -> row.get("url", String::class.java) }

	fun getAvatarUrlReactive(id: String): Mono<String> = mono { getAvatarUrlAsync(id) }
}
