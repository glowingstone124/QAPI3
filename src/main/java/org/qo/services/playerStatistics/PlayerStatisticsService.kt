package org.qo.services.playerStatistics

import com.google.gson.JsonObject
import kotlinx.coroutines.reactor.mono
import org.qo.datas.ReactiveDatabase
import org.qo.db.repository.PlayerStatisticsDbRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

data class PlayerStatisticsSnapshot(
	val name: String = "",
	val distanceCm: Long = 0,
	val damageDealt: Long = 0,
	val mobKills: Long = 0,
	val elytraFlightTicks: Long = 0,
)

data class PlayerStatistics(
	val distanceCm: Long = 0,
	val damageDealt: Long = 0,
	val mobKills: Long = 0,
	val blocksMined: Long = 0,
	val blocksPlaced: Long = 0,
	val elytraFlightTicks: Long = 0,
) {
	fun toJson(): JsonObject = JsonObject().apply {
		addProperty("distance_cm", distanceCm)
		addProperty("damage_dealt", damageDealt)
		addProperty("mob_kills", mobKills)
		addProperty("blocks_mined", blocksMined)
		addProperty("blocks_placed", blocksPlaced)
		addProperty("elytra_flight_ticks", elytraFlightTicks)
	}
}

@Service
class PlayerStatisticsService @Autowired constructor(
	private val repository: PlayerStatisticsDbRepository,
) {
	constructor(database: ReactiveDatabase) : this(PlayerStatisticsDbRepository(database))

	suspend fun upload(snapshots: List<PlayerStatisticsSnapshot>): Int {
		var updated = 0
		for (snapshot in snapshots
			.asSequence()
			.mapNotNull(::normalize)
			.take(MAX_PLAYERS_PER_UPLOAD)) {
			if (!repository.userExists(snapshot.name)) continue
			updated += repository.upsertSnapshot(snapshot, System.currentTimeMillis())
		}
		return updated
	}

	fun getPlayerStatisticsJsonReactive(name: String): Mono<JsonObject> = mono {
		getPlayerStatistics(name).toJson()
	}.onErrorReturn(PlayerStatistics().toJson())

	suspend fun getPlayerStatistics(name: String): PlayerStatistics {
		if (!PLAYER_NAME.matches(name)) return PlayerStatistics()
		val playerStatistics = repository.getPlayerStatistics(name) ?: PlayerStatistics()
		val blockStatistics = repository.getBlockStatistics(name)
		return playerStatistics.copy(blocksMined = blockStatistics.first, blocksPlaced = blockStatistics.second)
	}

	private fun normalize(snapshot: PlayerStatisticsSnapshot): PlayerStatisticsSnapshot? {
		if (!PLAYER_NAME.matches(snapshot.name)) return null
		if (listOf(
			snapshot.distanceCm,
			snapshot.damageDealt,
			snapshot.mobKills,
			snapshot.elytraFlightTicks,
		).any { it < 0 || it > MAX_STAT_VALUE }) return null
		return snapshot
	}

	private companion object {
		val PLAYER_NAME = Regex("^[A-Za-z0-9_]{3,16}$")
		const val MAX_PLAYERS_PER_UPLOAD = 200
		const val MAX_STAT_VALUE = 9_000_000_000_000_000L
	}
}
