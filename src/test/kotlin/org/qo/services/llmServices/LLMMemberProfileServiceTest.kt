package org.qo.services.llmServices

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LLMMemberProfileServiceTest {
	@Test
	fun `uses qq uid as stable profile identity and stores multiple fields`() = runBlocking {
		val service = LLMMemberProfileService(FakeMemberProfileRepository())
		val preference = service.upsertField(123456, "favorite_game", "Minecraft", "preference")!!
		val summary = service.upsertField(123456, "summary", "喜欢建筑和红石", "summary")!!

		assertEquals(preference.profile.profileId, summary.profile.profileId)
		val profile = service.profile(123456, null)
		assertNotNull(profile)
		assertEquals(setOf("favorite_game", "summary"), profile.fields.map { it.key }.toSet())
	}

	@Test
	fun `keeps group nickname scoped while sharing global preferences`() = runBlocking {
		val service = LLMMemberProfileService(FakeMemberProfileRepository())
		service.upsertField(42, "favorite_color", "blue", "preference")
		service.upsertField(42, "group_nickname", "Alice", "nickname", groupId = 100)
		service.upsertField(42, "group_nickname", "Builder", "nickname", groupId = 200)

		val group100 = service.profile(42, 100)!!
		val group200 = service.profile(42, 200)!!

		assertEquals("Alice", group100.fields.single { it.key == "group_nickname" }.value)
		assertEquals("Builder", group200.fields.single { it.key == "group_nickname" }.value)
		assertTrue(group100.fields.any { it.key == "favorite_color" && it.value == "blue" })
		assertTrue(group200.fields.any { it.key == "favorite_color" && it.value == "blue" })
	}

	@Test
	fun `isolates profiles by qq uid and deletes only requested scope`() = runBlocking {
		val service = LLMMemberProfileService(FakeMemberProfileRepository())
		val first = service.upsertField(1, "response_style", "concise", "preference")!!
		val second = service.upsertField(2, "response_style", "detailed", "preference")!!

		assertNotEquals(first.profile.profileId, second.profile.profileId)
		assertTrue(service.deleteField(1, "response_style", null))
		assertFalse(service.deleteField(1, "response_style", null))
		assertTrue(service.profile(1, null)!!.fields.isEmpty())
		assertEquals("detailed", service.profile(2, null)!!.fields.single().value)
		assertNull(service.profile(999, null))
	}

	private class FakeMemberProfileRepository : LLMMemberProfileRepository {
		private val profiles = linkedMapOf<Long, LLMStoredMemberProfileHeader>()
		private val fields = linkedMapOf<String, LLMMemberProfileField>()

		override suspend fun findProfile(uid: Long): LLMStoredMemberProfileHeader? = profiles[uid]

		override suspend fun findProfiles(uids: Collection<Long>): List<LLMStoredMemberProfileHeader> =
			uids.mapNotNull(profiles::get)

		override suspend fun insertProfile(profile: LLMStoredMemberProfileHeader): Boolean {
			if (profiles.containsKey(profile.qqUid)) return false
			profiles[profile.qqUid] = profile
			return true
		}

		override suspend fun touchProfile(uid: Long, updatedAt: Long) {
			profiles[uid]?.let { profiles[uid] = it.copy(updatedAt = updatedAt) }
		}

		override suspend fun findField(uid: Long, scopeGroupId: Long, fieldKey: String): LLMMemberProfileField? =
			fields.values.firstOrNull { it.qqUid == uid && it.scopeGroupId == scopeGroupId && it.key == fieldKey }

		override suspend fun findFields(uids: Collection<Long>, groupId: Long?): List<LLMMemberProfileField> {
			val scopes = setOfNotNull(LLMMemberProfileService.GLOBAL_SCOPE, groupId)
			return fields.values.filter { it.qqUid in uids && it.scopeGroupId in scopes }
		}

		override suspend fun insertField(field: LLMMemberProfileField): Boolean {
			if (findField(field.qqUid, field.scopeGroupId, field.key) != null) return false
			fields[field.id] = field
			return true
		}

		override suspend fun updateField(field: LLMMemberProfileField) {
			fields[field.id] = field
		}

		override suspend fun deleteField(uid: Long, scopeGroupId: Long, fieldKey: String): Boolean {
			val id = fields.values.firstOrNull {
				it.qqUid == uid && it.scopeGroupId == scopeGroupId && it.key == fieldKey
			}?.id ?: return false
			fields.remove(id)
			return true
		}
	}
}
