package org.qo.services.llmServices

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.qo.datas.ReactiveDatabase
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Repository

interface LLMMemberProfileRepository {
	suspend fun findProfile(uid: Long): LLMStoredMemberProfileHeader?
	suspend fun findProfiles(uids: Collection<Long>): List<LLMStoredMemberProfileHeader>
	suspend fun insertProfile(profile: LLMStoredMemberProfileHeader): Boolean
	suspend fun touchProfile(uid: Long, updatedAt: Long)
	suspend fun findField(uid: Long, scopeGroupId: Long, fieldKey: String): LLMMemberProfileField?
	suspend fun findFields(uids: Collection<Long>, groupId: Long?): List<LLMMemberProfileField>
	suspend fun insertField(field: LLMMemberProfileField): Boolean
	suspend fun updateField(field: LLMMemberProfileField)
	suspend fun deleteField(uid: Long, scopeGroupId: Long, fieldKey: String): Boolean
}

@Repository
class R2dbcLLMMemberProfileRepository(
	private val database: ReactiveDatabase,
) : LLMMemberProfileRepository {
	private val initializationScope = CoroutineScope(SupervisorJob())
	private val schemaReady = CompletableDeferred<Unit>()

	@EventListener(ApplicationReadyEvent::class)
	fun initializeSchema() {
		initializationScope.launch {
			try {
				database.execute(
					"""
					CREATE TABLE IF NOT EXISTS llm_member_profiles (
						qq_uid BIGINT PRIMARY KEY,
						profile_id VARCHAR(36) NOT NULL,
						created_at BIGINT NOT NULL,
						updated_at BIGINT NOT NULL,
						UNIQUE KEY uk_llm_member_profile_id (profile_id),
						INDEX idx_llm_member_profile_updated (updated_at)
					)
					""".trimIndent()
				)
				database.execute(
					"""
					CREATE TABLE IF NOT EXISTS llm_member_profile_fields (
						id VARCHAR(36) PRIMARY KEY,
						qq_uid BIGINT NOT NULL,
						scope_group_id BIGINT NOT NULL DEFAULT 0,
						field_key VARCHAR(80) NOT NULL,
						field_value TEXT NOT NULL,
						category VARCHAR(40) NOT NULL,
						source_uid VARCHAR(128) NULL,
						source_name VARCHAR(160) NULL,
						created_at BIGINT NOT NULL,
						updated_at BIGINT NOT NULL,
						UNIQUE KEY uk_llm_member_profile_field (qq_uid, scope_group_id, field_key),
						INDEX idx_llm_member_profile_field_uid (qq_uid, updated_at),
						CONSTRAINT fk_llm_member_profile_uid FOREIGN KEY (qq_uid)
							REFERENCES llm_member_profiles(qq_uid) ON DELETE CASCADE
					)
					""".trimIndent()
				)
				schemaReady.complete(Unit)
			} catch (error: Exception) {
				schemaReady.completeExceptionally(error)
				println("LLM member profile table init failed: ${error.message}")
			}
		}
	}

	@PreDestroy
	fun shutdown() {
		initializationScope.cancel()
	}

	override suspend fun findProfile(uid: Long): LLMStoredMemberProfileHeader? {
		schemaReady.await()
		return database.one(
			"SELECT qq_uid, profile_id, created_at, updated_at FROM llm_member_profiles WHERE qq_uid = ? LIMIT 1",
			listOf(uid),
			::toProfile,
		)
	}

	override suspend fun findProfiles(uids: Collection<Long>): List<LLMStoredMemberProfileHeader> {
		val distinct = uids.distinct()
		if (distinct.isEmpty()) return emptyList()
		schemaReady.await()
		return database.all(
			"SELECT qq_uid, profile_id, created_at, updated_at FROM llm_member_profiles WHERE qq_uid IN (${placeholders(distinct.size)})",
			distinct,
			::toProfile,
		)
	}

	override suspend fun insertProfile(profile: LLMStoredMemberProfileHeader): Boolean {
		schemaReady.await()
		return database.execute(
			"INSERT IGNORE INTO llm_member_profiles(qq_uid, profile_id, created_at, updated_at) VALUES (?, ?, ?, ?)",
			listOf(profile.qqUid, profile.profileId, profile.createdAt, profile.updatedAt),
		) == 1L
	}

	override suspend fun touchProfile(uid: Long, updatedAt: Long) {
		schemaReady.await()
		database.execute(
			"UPDATE llm_member_profiles SET updated_at = ? WHERE qq_uid = ?",
			listOf(updatedAt, uid),
		)
	}

	override suspend fun findField(uid: Long, scopeGroupId: Long, fieldKey: String): LLMMemberProfileField? {
		schemaReady.await()
		return database.one(
			"""
			SELECT id, qq_uid, scope_group_id, field_key, field_value, category, source_uid, source_name, created_at, updated_at
			FROM llm_member_profile_fields
			WHERE qq_uid = ? AND scope_group_id = ? AND field_key = ?
			LIMIT 1
			""".trimIndent(),
			listOf(uid, scopeGroupId, fieldKey),
			::toField,
		)
	}

	override suspend fun findFields(uids: Collection<Long>, groupId: Long?): List<LLMMemberProfileField> {
		val distinct = uids.distinct()
		if (distinct.isEmpty()) return emptyList()
		schemaReady.await()
		val scopes = if (groupId == null) listOf(GLOBAL_SCOPE) else listOf(GLOBAL_SCOPE, groupId)
		return database.all(
			"""
			SELECT id, qq_uid, scope_group_id, field_key, field_value, category, source_uid, source_name, created_at, updated_at
			FROM llm_member_profile_fields
			WHERE qq_uid IN (${placeholders(distinct.size)}) AND scope_group_id IN (${placeholders(scopes.size)})
			ORDER BY updated_at DESC
			""".trimIndent(),
			distinct + scopes,
			::toField,
		)
	}

	override suspend fun insertField(field: LLMMemberProfileField): Boolean {
		schemaReady.await()
		return database.execute(
			"""
			INSERT IGNORE INTO llm_member_profile_fields
			(id, qq_uid, scope_group_id, field_key, field_value, category, source_uid, source_name, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""".trimIndent(),
			field.bindings(),
		) == 1L
	}

	override suspend fun updateField(field: LLMMemberProfileField) {
		schemaReady.await()
		database.execute(
			"""
			UPDATE llm_member_profile_fields
			SET field_value = ?, category = ?, source_uid = ?, source_name = ?, updated_at = ?
			WHERE id = ? AND qq_uid = ?
			""".trimIndent(),
			listOf(field.value, field.category, field.sourceUid, field.sourceName, field.updatedAt, field.id, field.qqUid),
		)
	}

	override suspend fun deleteField(uid: Long, scopeGroupId: Long, fieldKey: String): Boolean {
		schemaReady.await()
		return database.execute(
			"DELETE FROM llm_member_profile_fields WHERE qq_uid = ? AND scope_group_id = ? AND field_key = ?",
			listOf(uid, scopeGroupId, fieldKey),
		) > 0
	}

	private fun LLMMemberProfileField.bindings(): List<Any?> = listOf(
		id, qqUid, scopeGroupId, key, value, category, sourceUid, sourceName, createdAt, updatedAt,
	)

	private fun toProfile(row: io.r2dbc.spi.Row): LLMStoredMemberProfileHeader = LLMStoredMemberProfileHeader(
		qqUid = row.get("qq_uid", java.lang.Long::class.java)!!.toLong(),
		profileId = row.get("profile_id", String::class.java)!!,
		createdAt = row.get("created_at", java.lang.Long::class.java)!!.toLong(),
		updatedAt = row.get("updated_at", java.lang.Long::class.java)!!.toLong(),
	)

	private fun toField(row: io.r2dbc.spi.Row): LLMMemberProfileField = LLMMemberProfileField(
		id = row.get("id", String::class.java)!!,
		qqUid = row.get("qq_uid", java.lang.Long::class.java)!!.toLong(),
		scopeGroupId = row.get("scope_group_id", java.lang.Long::class.java)!!.toLong(),
		key = row.get("field_key", String::class.java)!!,
		value = row.get("field_value", String::class.java)!!,
		category = row.get("category", String::class.java)!!,
		sourceUid = row.get("source_uid", String::class.java),
		sourceName = row.get("source_name", String::class.java),
		createdAt = row.get("created_at", java.lang.Long::class.java)!!.toLong(),
		updatedAt = row.get("updated_at", java.lang.Long::class.java)!!.toLong(),
	)

	private fun placeholders(size: Int): String = List(size) { "?" }.joinToString(", ")

	private companion object {
		const val GLOBAL_SCOPE = 0L
	}
}

data class LLMStoredMemberProfileHeader(
	val qqUid: Long,
	val profileId: String,
	val createdAt: Long,
	val updatedAt: Long,
)

data class LLMMemberProfileField(
	val id: String,
	val qqUid: Long,
	val scopeGroupId: Long,
	val key: String,
	val value: String,
	val category: String,
	val sourceUid: String?,
	val sourceName: String?,
	val createdAt: Long,
	val updatedAt: Long,
)

data class LLMStoredMemberProfile(
	val qqUid: Long,
	val profileId: String,
	val fields: List<LLMMemberProfileField>,
	val createdAt: Long,
	val updatedAt: Long,
)
