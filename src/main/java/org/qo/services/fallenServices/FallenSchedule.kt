package org.qo.services.fallenServices

import java.time.LocalDate
import java.time.ZoneId

internal object FallenSchedule {
	const val ASSIGNMENT_ZONE_ID = "Asia/Shanghai"
	const val ASSIGNMENT_CRON = "0 59 23 20 9 *"

	val assignmentZone: ZoneId = ZoneId.of(ASSIGNMENT_ZONE_ID)
	val assignmentInstant = LocalDate.of(2026, 9, 20)
		.atTime(23, 59)
		.atZone(assignmentZone)
		.toInstant()
}
