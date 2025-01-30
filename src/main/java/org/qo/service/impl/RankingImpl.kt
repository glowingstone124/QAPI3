package org.qo.service.impl

import com.google.gson.Gson
import kotlinx.coroutines.reactive.awaitFirst
import org.qo.orm.SQL
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux

@Service
class RankingImpl {
	val gson = Gson()
	val sqlCheckExistence = "SELECT username FROM users WHERE username IN (:usernames)"
	val sqlUpdateDestroy = "UPDATE users SET destroy = destroy + ? WHERE username = ?"
	val sqlUpdatePlace = "UPDATE users SET place = place + ? WHERE username = ?"
	val sqlQueryDestroy = "SELECT username, destroy FROM users WHERE destroy > 0 ORDER BY destroy DESC LIMIT 5"
	val sqlQueryPlace = "SELECT username, place FROM users WHERE place > 0 ORDER BY place DESC LIMIT 5"

	enum class Rank {
		DESTROY,
		PLACE
	}

	suspend fun insertDestroyRanking(body: String) {
		val destroyData: Map<String, Long> = gson.fromJson(body, Map::class.java) as Map<String, Long>
		val validDestroyData = doFilter(destroyData)
		val connection = SQL.getConnection()
		val inserts = validDestroyData.map { (username, destroy) ->
			connection.createStatement(sqlUpdateDestroy)
				.bind(0, destroy)
				.bind(1, username)
				.execute()
		}
		Flux.concat(inserts)
			.then()
			.awaitFirst()
	}

	suspend fun insertPlaceRanking(body: String) {
		val placeData: Map<String, Long> = gson.fromJson(body, Map::class.java) as Map<String, Long>
		val validPlaceData = doFilter(placeData)
		val connection = SQL.getConnection()
		val inserts = validPlaceData.map { (username, place) ->
			connection.createStatement(sqlUpdatePlace)
				.bind(0, place)
				.bind(1, username)
				.execute()
		}
		Flux.concat(inserts)
			.then()
			.awaitFirst()
	}

	suspend fun doFilter(map: Map<String, Long>): Map<String, Long> {
		return map.filter { (username, _) -> username in checkUsersExistence(map.keys.toList()) }
	}

	suspend fun checkUsersExistence(usernames: List<String>): List<String> {
		return SQL.getConnection()
			.createStatement(sqlCheckExistence)
			.bind(":usernames", usernames)
			.execute()
			.flatMap { result ->
				result.map { row, _ -> row.get("username", String::class.java) }
					.collectList()
			}
			.awaitFirst()
	}

	suspend fun getRanking(type: Rank): Map<String, Long> {
		return SQL.getConnection()
			.createStatement(
				when(type) {
					Rank.DESTROY -> sqlQueryDestroy
					Rank.PLACE -> sqlQueryPlace
				}
			)
			.execute()
			.flatMap { result ->
				result.map { column ->
					val username = column.get("username", String::class.java)
					val destroy = column.get("destroy", Long::class.java)
					if (username != null && destroy != null) {
						username to destroy
					} else {
						null
					}
				}.collectList()
			}
			.awaitFirst()
			.filterNotNull()
			.toMap()
	}

}