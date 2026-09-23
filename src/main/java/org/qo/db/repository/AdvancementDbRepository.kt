package org.qo.db.repository

import io.r2dbc.spi.Row
import org.qo.datas.ReactiveDatabase
import org.springframework.stereotype.Repository

@Repository
class AdvancementDbRepository(
	private val database: ReactiveDatabase,
) {
	data class AdvancementRecord(
		val id: Long,
		val name: String,
		val description: String,
	)

	suspend fun getCompleteAdvancements(username: String): List<AdvancementRecord> = database.all(
		"""
		SELECT a.id, a.name, a.description
		FROM advancement_completed ac
		JOIN advancements a ON ac.advancement_id = a.id
		WHERE ac.player_username = ?
		""".trimIndent(),
		listOf(username),
		::toAdvancement,
	)

	suspend fun getAchievementCompletePlayerCount(advancementId: Long): Long =
		database.one(
			"SELECT COUNT(*) AS cnt FROM advancement_completed WHERE advancement_id = ?",
			listOf(advancementId),
		) { row -> row.get("cnt", java.lang.Long::class.java)?.toLong() ?: 0L } ?: 0L

	suspend fun userExists(player: String): Boolean =
		database.one("SELECT 1 FROM users WHERE username = ? LIMIT 1", listOf(player)) { true } != null

	suspend fun hasCompleted(player: String, advancementId: Long): Boolean =
		database.one(
			"SELECT 1 FROM advancement_completed WHERE player_username = ? AND advancement_id = ? LIMIT 1",
			listOf(player, advancementId),
		) { true } != null

	suspend fun insertCompletion(player: String, advancementId: Long): Boolean =
		database.execute(
			"INSERT INTO advancement_completed(player_username, advancement_id) VALUES (?, ?)",
			listOf(player, advancementId),
		) > 0

	suspend fun getAllAdvancements(): List<AdvancementRecord> = database.all(
		"SELECT id, name, description FROM advancements",
		mapper = ::toAdvancement,
	)

	suspend fun <T> inTransaction(block: suspend () -> T): T = database.inTransaction(block)

	private fun toAdvancement(row: Row): AdvancementRecord = AdvancementRecord(
		id = row.get("id", java.lang.Long::class.java)!!.toLong(),
		name = row.get("name", String::class.java)!!,
		description = row.get("description", String::class.java)!!,
	)
}
