package org.qo.db.repository

import org.qo.datas.ReactiveDatabase
import org.qo.services.rankingServices.RankingKind
import org.qo.services.rankingServices.RankingStore
import org.springframework.stereotype.Repository

@Repository
class RankingDbRepository(
	private val database: ReactiveDatabase,
) : RankingStore {
	override suspend fun read(kind: RankingKind, limit: Int): Map<String, Long> {
		val sql = "SELECT username, ${kind.columnName} FROM users WHERE ${kind.columnName} > 0 ORDER BY ${kind.columnName} DESC, username ASC LIMIT ?"
		return database.all(sql, listOf(limit.coerceIn(1, 100))) { row ->
			row.get("username", String::class.java)!! to (row.get(kind.columnName, java.lang.Long::class.java)?.toLong() ?: 0L)
		}.associateTo(linkedMapOf()) { it }
	}

	override suspend fun increment(kind: RankingKind, delta: Map<String, Long>): Int {
		if (delta.isEmpty()) return 0
		val sql = "UPDATE users SET ${kind.columnName} = COALESCE(${kind.columnName}, 0) + ? WHERE username = ?"
		return database.inTransaction {
			delta.entries.sumOf { (username, amount) ->
				if (database.execute(sql, listOf(amount, username)) > 0) 1 else 0
			}
		}
	}
}
