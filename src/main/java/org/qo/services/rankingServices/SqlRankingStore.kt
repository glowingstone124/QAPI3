package org.qo.services.rankingServices

import org.qo.datas.ReactiveDatabase
import org.qo.db.repository.RankingDbRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service

@Service
@Primary
class SqlRankingStore @Autowired constructor(
	private val repository: RankingDbRepository,
) : RankingStore {
	constructor(database: ReactiveDatabase) : this(RankingDbRepository(database))

	override suspend fun read(kind: RankingKind, limit: Int): Map<String, Long> =
		repository.read(kind, limit)

	override suspend fun increment(kind: RankingKind, delta: Map<String, Long>): Int =
		repository.increment(kind, delta)
}
