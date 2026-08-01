package org.qo.services.fallenServices

import java.time.LocalDate
import java.time.ZoneId

internal object FallenSchedule {
	const val ASSIGNMENT_ZONE_ID = "Asia/Shanghai"
	const val ASSIGNMENT_CRON = "0 0 0 1 10 *"

	val assignmentZone: ZoneId = ZoneId.of(ASSIGNMENT_ZONE_ID)
	val assignmentInstant = LocalDate.of(2026, 10, 1)
		.atStartOfDay(assignmentZone)
		.toInstant()
}
