package org.qo.services.fallenServices

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class FallenScheduleTest {
	@Test
	fun assignmentClosesAt2359AsiaShanghaiOnSeptemberTwentieth() {
		assertEquals("Asia/Shanghai", FallenSchedule.assignmentZone.id)
		assertEquals("0 59 23 20 9 *", FallenSchedule.ASSIGNMENT_CRON)
		assertEquals(
			LocalDateTime.of(2026, 9, 20, 15, 59).toInstant(ZoneOffset.UTC),
			FallenSchedule.assignmentInstant,
		)
	}
}
