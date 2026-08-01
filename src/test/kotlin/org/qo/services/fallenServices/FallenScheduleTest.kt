package org.qo.services.fallenServices

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class FallenScheduleTest {
	@Test
	fun assignmentClosesAtMidnightAsiaShanghaiOnOctoberFirst() {
		assertEquals("Asia/Shanghai", FallenSchedule.assignmentZone.id)
		assertEquals("0 0 0 1 10 *", FallenSchedule.ASSIGNMENT_CRON)
		assertEquals(
			LocalDateTime.of(2026, 9, 30, 16, 0).toInstant(ZoneOffset.UTC),
			FallenSchedule.assignmentInstant,
		)
	}
}
