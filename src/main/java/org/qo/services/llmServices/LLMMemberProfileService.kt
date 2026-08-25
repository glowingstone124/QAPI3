package org.qo.services.llmServices

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Service
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class LLMMemberProfileService(
	private val repository: LLMMemberProfileRepository,
) {
	private val locks = ConcurrentHashMap<Long, Mutex>()

	suspend fun observeRequester(uid: Long, name: String, groupId: Long?) {
		val key = if (groupId == null) "display_name" else "group_nickname"
		upsertField(
			uid = uid,
			fieldKey = key,
			value = name,
			category = "identity",
			groupId = groupId,
			sourceUid = uid.toString(),
			sourceName = name,
		)
	}

	suspend fun upsertField(
		uid: Long,
		fieldKey: String,
		value: String,
		category: String = "general",
		groupId: Long? = null,
		sourceUid: String? = null,
		sourceName: String? = null,
	): MemberProfileMutation? {
		if (uid <= 0) return null
		val normalizedKey = normalizeKey(fieldKey)
		val normalizedValue = normalize(value).take(if (normalizedKey == "summary") 6000 else 2000)
		val normalizedCategory = normalizeCategory(category)
		if (normalizedKey.isBlank() || normalizedValue.isBlank()) return null
		val scopeGroupId = groupId ?: GLOBAL_SCOPE
		return lockFor(uid).withLock {
			val profile = ensureProfile(uid)
			val existing = repository.findField(uid, scopeGroupId, normalizedKey)
			val now = System.currentTimeMillis()
			if (existing != null) {
				if (existing.value == normalizedValue && existing.category == normalizedCategory) {
					return@withLock MemberProfileMutation(profile, existing, created = false, changed = false)
				}
				val updated = existing.copy(
					value = normalizedValue,
					category = normalizedCategory,
					sourceUid = sourceUid?.take(128) ?: existing.sourceUid,
					sourceName = sourceName?.take(160) ?: existing.sourceName,
					updatedAt = now,
				)
				repository.updateField(updated)
				repository.touchProfile(uid, now)
				MemberProfileMutation(profile.copy(updatedAt = now), updated, created = false, changed = true)
			} else {
				val created = LLMMemberProfileField(
					id = UUID.randomUUID().toString(),
					qqUid = uid,
					scopeGroupId = scopeGroupId,
					key = normalizedKey,
					value = normalizedValue,
					category = normalizedCategory,
					sourceUid = sourceUid?.take(128),
					sourceName = sourceName?.take(160),
					createdAt = now,
					updatedAt = now,
				)
				if (repository.insertField(created)) {
					repository.touchProfile(uid, now)
					MemberProfileMutation(profile.copy(updatedAt = now), created, created = true, changed = true)
				} else {
					val concurrent = repository.findField(uid, scopeGroupId, normalizedKey)
						?: error("profile field insert was ignored but no existing field was found")
					val updated = concurrent.copy(
						value = normalizedValue,
						category = normalizedCategory,
						sourceUid = sourceUid?.take(128) ?: concurrent.sourceUid,
						sourceName = sourceName?.take(160) ?: concurrent.sourceName,
						updatedAt = now,
					)
					repository.updateField(updated)
					repository.touchProfile(uid, now)
					MemberProfileMutation(profile.copy(updatedAt = now), updated, created = false, changed = true)
				}
			}
		}
	}

	suspend fun profile(uid: Long, groupId: Long?): LLMStoredMemberProfile? = profiles(listOf(uid), groupId).singleOrNull()

	suspend fun profiles(uids: Collection<Long>, groupId: Long?): List<LLMStoredMemberProfile> {
		val normalizedUids = uids.filter { it > 0 }.distinct().take(100)
		if (normalizedUids.isEmpty()) return emptyList()
		val headers = repository.findProfiles(normalizedUids).associateBy { it.qqUid }
		val fields = repository.findFields(headers.keys, groupId).groupBy { it.qqUid }
		return normalizedUids.mapNotNull { uid ->
			headers[uid]?.let { header ->
				LLMStoredMemberProfile(uid, header.profileId, fields[uid].orEmpty(), header.createdAt, header.updatedAt)
			}
		}
	}

	suspend fun deleteField(uid: Long, fieldKey: String, groupId: Long?): Boolean {
		if (uid <= 0) return false
		val key = normalizeKey(fieldKey)
		if (key.isBlank()) return false
		return lockFor(uid).withLock {
			repository.deleteField(uid, groupId ?: GLOBAL_SCOPE, key).also { removed ->
				if (removed) repository.touchProfile(uid, System.currentTimeMillis())
			}
		}
	}

	private suspend fun ensureProfile(uid: Long): LLMStoredMemberProfileHeader {
		repository.findProfile(uid)?.let { return it }
		val now = System.currentTimeMillis()
		val created = LLMStoredMemberProfileHeader(uid, UUID.randomUUID().toString(), now, now)
		return if (repository.insertProfile(created)) created else repository.findProfile(uid)
			?: error("profile insert was ignored but no profile was found")
	}

	private fun normalize(value: String): String = value.trim().replace(Regex("\\s+"), " ")

	private fun normalizeKey(value: String): String = normalize(value)
		.lowercase(Locale.ROOT)
		.replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
		.trim('_')
		.take(80)

	private fun normalizeCategory(value: String): String = normalize(value)
		.lowercase(Locale.ROOT)
		.replace(Regex("[^a-z0-9_-]"), "_")
		.trim('_')
		.take(40)
		.ifBlank { "general" }

	private fun lockFor(uid: Long): Mutex = locks.computeIfAbsent(uid) { Mutex() }

	companion object {
		const val GLOBAL_SCOPE = 0L
	}
}

data class MemberProfileMutation(
	val profile: LLMStoredMemberProfileHeader,
	val field: LLMMemberProfileField,
	val created: Boolean,
	val changed: Boolean,
)
