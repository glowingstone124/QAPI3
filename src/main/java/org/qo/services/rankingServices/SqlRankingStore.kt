package org.qo.services.rankingServices

import org.qo.datas.ConnectionPool
import org.springframework.stereotype.Service

@Service
class SqlRankingStore : RankingStore {
	override fun read(kind: RankingKind): Map<String, Long> {
		val sql = "SELECT username, ${kind.columnName} FROM users WHERE ${kind.columnName} > 0 ORDER BY ${kind.columnName} DESC"
		ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement(sql).use { statement ->
				statement.executeQuery().use { resultSet ->
					val result = linkedMapOf<String, Long>()
					while (resultSet.next()) {
						result[resultSet.getString("username")] = resultSet.getLong(kind.columnName)
					}
					return result
				}
			}
		}
	}

	override fun increment(kind: RankingKind, delta: Map<String, Long>): Int {
		if (delta.isEmpty()) return 0
		val sql = "UPDATE users SET ${kind.columnName} = COALESCE(${kind.columnName}, 0) + ? WHERE username = ?"
		ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement(sql).use { statement ->
				delta.forEach { (username, amount) ->
					statement.setLong(1, amount)
					statement.setString(2, username)
					statement.addBatch()
				}
				return statement.executeBatch().sum()
			}
		}
	}
}
