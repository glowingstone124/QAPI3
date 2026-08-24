package org.qo.services.playerStatistics

import com.google.gson.JsonObject
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.qo.datas.ReactiveDatabase
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

data class PlayerStatisticsSnapshot(
	val name: String = "",
	val distanceCm: Long = 0,
	val damageDealt: Long = 0,
	val mobKills: Long = 0,
	val blocksMined: Long = 0,
	val blocksPlaced: Long = 0,
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
class PlayerStatisticsService(
	private val database: ReactiveDatabase,
) {
	private val schemaMutex = Mutex()
	@Volatile
	private var schemaReady = false

	private val createTableSql = """
		CREATE TABLE IF NOT EXISTS player_statistics (
			username VARCHAR(16) NOT NULL PRIMARY KEY,
			distance_cm BIGINT NOT NULL DEFAULT 0,
			damage_dealt BIGINT NOT NULL DEFAULT 0,
			mob_kills BIGINT NOT NULL DEFAULT 0,
			blocks_mined BIGINT NOT NULL DEFAULT 0,
			blocks_placed BIGINT NOT NULL DEFAULT 0,
			elytra_flight_ticks BIGINT NOT NULL DEFAULT 0,
			updated_at BIGINT NOT NULL DEFAULT 0
		)
	""".trimIndent()

	private val upsertSql = """
		INSERT INTO player_statistics (
			username, distance_cm, damage_dealt, mob_kills, blocks_mined, blocks_placed, elytra_flight_ticks, updated_at
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?) AS incoming
		ON DUPLICATE KEY UPDATE
			distance_cm = GREATEST(distance_cm, incoming.distance_cm),
			damage_dealt = GREATEST(damage_dealt, incoming.damage_dealt),
			mob_kills = GREATEST(mob_kills, incoming.mob_kills),
			blocks_mined = GREATEST(blocks_mined, incoming.blocks_mined),
			blocks_placed = GREATEST(blocks_placed, incoming.blocks_placed),
			elytra_flight_ticks = GREATEST(elytra_flight_ticks, incoming.elytra_flight_ticks),
			updated_at = GREATEST(updated_at, incoming.updated_at)
	""".trimIndent()

	suspend fun upload(snapshots: List<PlayerStatisticsSnapshot>): Int {
		ensureTable()
		var updated = 0
		for (snapshot in snapshots
			.asSequence()
			.mapNotNull(::normalize)
			.take(MAX_PLAYERS_PER_UPLOAD)) {
			if (!userExists(snapshot.name)) continue
			updated += database.execute(
				upsertSql,
				listOf(
					snapshot.name,
					snapshot.distanceCm,
					snapshot.damageDealt,
					snapshot.mobKills,
					snapshot.blocksMined,
					snapshot.blocksPlaced,
					snapshot.elytraFlightTicks,
					System.currentTimeMillis(),
				),
			).toInt()
		}
		return updated
	}

	fun getPlayerStatisticsJsonReactive(name: String): Mono<JsonObject> = mono {
		getPlayerStatistics(name).toJson()
	}.onErrorReturn(PlayerStatistics().toJson())

	suspend fun getPlayerStatistics(name: String): PlayerStatistics {
		if (!PLAYER_NAME.matches(name)) return PlayerStatistics()
		ensureTable()
		return database.one(
			"""
			SELECT distance_cm, damage_dealt, mob_kills, blocks_mined, blocks_placed, elytra_flight_ticks
			FROM player_statistics WHERE username = ? LIMIT 1
			""".trimIndent(),
			listOf(name),
		) { row ->
			PlayerStatistics(
				distanceCm = row.get("distance_cm", java.lang.Long::class.java)?.toLong() ?: 0,
				damageDealt = row.get("damage_dealt", java.lang.Long::class.java)?.toLong() ?: 0,
				mobKills = row.get("mob_kills", java.lang.Long::class.java)?.toLong() ?: 0,
				blocksMined = row.get("blocks_mined", java.lang.Long::class.java)?.toLong() ?: 0,
				blocksPlaced = row.get("blocks_placed", java.lang.Long::class.java)?.toLong() ?: 0,
				elytraFlightTicks = row.get("elytra_flight_ticks", java.lang.Long::class.java)?.toLong() ?: 0,
			)
		} ?: PlayerStatistics()
	}

	private suspend fun ensureTable() {
		if (schemaReady) return
		schemaMutex.withLock {
			if (schemaReady) return
			database.execute(createTableSql)
			schemaReady = true
		}
	}

	private suspend fun userExists(name: String): Boolean = database.one(
		"SELECT 1 FROM users WHERE username = ? LIMIT 1",
		listOf(name),
	) { true } != null

	private fun normalize(snapshot: PlayerStatisticsSnapshot): PlayerStatisticsSnapshot? {
		if (!PLAYER_NAME.matches(snapshot.name)) return null
		if (listOf(
			snapshot.distanceCm,
			snapshot.damageDealt,
			snapshot.mobKills,
			snapshot.blocksMined,
			snapshot.blocksPlaced,
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
