package org.qo.db.repository

import io.r2dbc.spi.Row
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.qo.datas.ReactiveDatabase
import org.qo.services.playerStatistics.PlayerStatistics
import org.qo.services.playerStatistics.PlayerStatisticsSnapshot
import org.springframework.stereotype.Repository

@Repository
class PlayerStatisticsDbRepository(
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
			elytra_flight_ticks BIGINT NOT NULL DEFAULT 0,
			updated_at BIGINT NOT NULL DEFAULT 0
		)
	""".trimIndent()

	private val upsertSql = """
		INSERT INTO player_statistics (
			username, distance_cm, damage_dealt, mob_kills, elytra_flight_ticks, updated_at
		) VALUES (?, ?, ?, ?, ?, ?) AS incoming
		ON DUPLICATE KEY UPDATE
			distance_cm = GREATEST(distance_cm, incoming.distance_cm),
			damage_dealt = GREATEST(damage_dealt, incoming.damage_dealt),
			mob_kills = GREATEST(mob_kills, incoming.mob_kills),
			elytra_flight_ticks = GREATEST(elytra_flight_ticks, incoming.elytra_flight_ticks),
			updated_at = GREATEST(updated_at, incoming.updated_at)
	""".trimIndent()

	suspend fun ensureTable() {
		if (schemaReady) return
		schemaMutex.withLock {
			if (schemaReady) return
			database.execute(createTableSql)
			schemaReady = true
		}
	}

	suspend fun userExists(username: String): Boolean =
		database.one("SELECT 1 FROM users WHERE username = ? LIMIT 1", listOf(username)) { true } != null

	suspend fun upsertSnapshot(snapshot: PlayerStatisticsSnapshot, now: Long): Int {
		ensureTable()
		return database.execute(
			upsertSql,
			listOf(
				snapshot.name,
				snapshot.distanceCm,
				snapshot.damageDealt,
				snapshot.mobKills,
				snapshot.elytraFlightTicks,
				now,
			),
		).toInt()
	}

	suspend fun getPlayerStatistics(name: String): PlayerStatistics? {
		ensureTable()
		return database.one(
			"""
			SELECT distance_cm, damage_dealt, mob_kills, elytra_flight_ticks
			FROM player_statistics WHERE username = ? LIMIT 1
			""".trimIndent(),
			listOf(name),
		) { row ->
			PlayerStatistics(
				distanceCm = row.get("distance_cm", java.lang.Long::class.java)?.toLong() ?: 0,
				damageDealt = row.get("damage_dealt", java.lang.Long::class.java)?.toLong() ?: 0,
				mobKills = row.get("mob_kills", java.lang.Long::class.java)?.toLong() ?: 0,
				elytraFlightTicks = row.get("elytra_flight_ticks", java.lang.Long::class.java)?.toLong() ?: 0,
			)
		}
	}

	suspend fun getBlockStatistics(name: String): Pair<Long, Long> {
		return database.one(
			"SELECT COALESCE(destroy, 0) AS blocks_mined, COALESCE(place, 0) AS blocks_placed FROM users WHERE username = ? LIMIT 1",
			listOf(name),
		) { row ->
			(row.get("blocks_mined", java.lang.Long::class.java)?.toLong() ?: 0) to
				(row.get("blocks_placed", java.lang.Long::class.java)?.toLong() ?: 0)
		} ?: (0L to 0L)
	}
}
