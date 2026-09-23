package org.qo.services.loginService

import kotlinx.coroutines.reactor.mono
import org.qo.datas.ReactiveDatabase
import org.qo.db.repository.AvatarDbRepository
import org.qo.orm.unsupportedSyncApi
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

@Service
class AvatarRelatedImpl {
	private var repositoryOverride: AvatarDbRepository? = null

	constructor()

	@Autowired
	constructor(repository: AvatarDbRepository) {
		this.repositoryOverride = repository
	}

	constructor(database: ReactiveDatabase) : this(AvatarDbRepository(database))

	private val repository: AvatarDbRepository
		get() = repositoryOverride ?: org.qo.utils.SpringContextUtil.ctx.getBean(AvatarDbRepository::class.java)

	fun getAvatarUrl(id: String): String? = unsupportedSyncApi("AvatarRelatedImpl.getAvatarUrl")

	suspend fun getAvatarUrlAsync(id: String): String? = repository.getAvatarUrl(id)

	fun getAvatarUrlReactive(id: String): Mono<String> = mono { getAvatarUrlAsync(id) }
}
