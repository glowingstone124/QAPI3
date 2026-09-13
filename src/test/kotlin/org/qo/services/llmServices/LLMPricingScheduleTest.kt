package org.qo.services.llmServices

import com.google.gson.JsonParser
import java.time.Instant
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class LLMPricingScheduleTest {
    private fun price() = LLMModelPricing.fromJson(JsonParser.parseString("""{
        "inputCnyPerMillion":1,"outputCnyPerMillion":4,"cachedInputCnyPerMillion":0.02,
        "schedule":{"zone":"Asia/Shanghai","weekdays":[1,2,3,4,5],"windows":[["09:00","12:00"],["14:00","18:00"]],"multiplier":2}
    }""").asJsonObject)

    @Test fun `peak pricing respects Shanghai boundaries lunch and weekends`() {
        for ((time,expected) in listOf(
            "2026-09-14T00:59:59Z" to "1", "2026-09-14T01:00:00Z" to "2",
            "2026-09-14T03:59:59Z" to "2", "2026-09-14T04:00:00Z" to "1",
            "2026-09-14T05:59:59Z" to "1", "2026-09-14T06:00:00Z" to "2",
            "2026-09-14T09:59:59Z" to "2", "2026-09-14T10:00:00Z" to "1",
            "2026-09-13T02:00:00Z" to "1"
        )) assertEquals(BigDecimal(expected),price().at(Instant.parse(time)).inputCnyPerMillion)
    }

    @Test fun `request price freezes all token rates and does not apply multiplier twice`() {
        val fixed = price().at(Instant.parse("2026-09-14T01:00:00Z"))
        assertNull(fixed.schedule)
        assertEquals(BigDecimal("8"),fixed.outputCnyPerMillion)
        assertEquals(BigDecimal("0.04"),fixed.cachedInputCnyPerMillion)
        assertEquals(fixed,fixed.at(Instant.parse("2026-09-14T12:00:00Z")))
        assertEquals(BigDecimal("9.02"),fixed.cost(1000000,1000000,500000))
    }

    @Test fun `configured schedules reject invalid boundaries and days`() {
        assertFailsWith<IllegalArgumentException> { LLMPricingSchedule(java.time.ZoneId.of("UTC"),setOf(0),listOf(java.time.LocalTime.MIN to java.time.LocalTime.NOON),BigDecimal.ONE) }
        assertFailsWith<IllegalArgumentException> { LLMPricingSchedule(java.time.ZoneId.of("UTC"),setOf(1),listOf(java.time.LocalTime.NOON to java.time.LocalTime.MIN),BigDecimal.ONE) }
    }
}
