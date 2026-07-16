package org.qo.services.fallenServices

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FallenTeamAllocatorTest {
	@Test
	fun allocate_balancesHeavilySkewedPreferences() {
		val registrations = (1..10).map {
			FallenRegistration("player$it", FallenTeam.A, it.toLong())
		}

		val result = FallenTeamAllocator.allocate(registrations)
		val counts = FallenTeam.entries.map { team -> result.values.count { it == team } }

		assertEquals(10, result.size)
		assertTrue(counts.max() - counts.min() <= 1, "team sizes must differ by at most one")
	}

	@Test
	fun allocate_keepsEveryPreferenceWhenAlreadyBalanced() {
		val registrations = listOf(
			FallenRegistration("a1", FallenTeam.A, 1),
			FallenRegistration("a2", FallenTeam.A, 2),
			FallenRegistration("b1", FallenTeam.B, 3),
			FallenRegistration("b2", FallenTeam.B, 4),
			FallenRegistration("c1", FallenTeam.C, 5),
			FallenRegistration("c2", FallenTeam.C, 6),
		)

		val result = FallenTeamAllocator.allocate(registrations)

		registrations.forEach { assertEquals(it.expectedTeam, result[it.username]) }
	}
}
