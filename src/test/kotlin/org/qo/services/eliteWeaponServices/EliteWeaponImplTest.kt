package org.qo.services.eliteWeaponServices

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.Mockito

class EliteWeaponImplTest {
	@Test
	fun batchedStatsUseOneConditionalUpdate() = runTest {
		val db = Mockito.mock(EliteWeaponDB::class.java)
		Mockito.`when`(db.addWeaponStats("weapon-id", "owner", 125L, 3L)).thenReturn(true)
		val service = EliteWeaponImpl(db)

		assertEquals("ok->SQL execution", service.addEliteWeaponStats("weapon-id", "owner", 125L, 3L))
		Mockito.verify(db, Mockito.times(1)).addWeaponStats("weapon-id", "owner", 125L, 3L)
		Mockito.verifyNoMoreInteractions(db)
	}

	@Test
	fun batchedStatsRejectUnknownWeapon() = runTest {
		val db = Mockito.mock(EliteWeaponDB::class.java)
		Mockito.`when`(db.addWeaponStats("missing", "owner", 10L, 0L)).thenReturn(false)
		val service = EliteWeaponImpl(db)

		assertEquals("err:user & uuid doesn't match", service.addEliteWeaponStats("missing", "owner", 10L, 0L))
	}
}
