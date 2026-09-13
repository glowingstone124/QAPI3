package org.qo.services.llmServices

import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LLMUsageAccountingTest {
    private val services = Mockito.mock(LLMServices::class.java)

    @Test fun `tool rounds retain costs and survive nullable optional usage fields`() {
        val total = accumulateUsage(LLMServices.Usage(10, 20), LLMServices.Usage(30, 40))
        val parsed = assertNotNull(services.parseUsage(withUsage("{}", total)))
        assertEquals(40, parsed.promptTokens)
        assertEquals(60, parsed.completionTokens)
        assertEquals(2, parsed.apiCalls)
        assertTrue(parsed.complete)
        // A single call with no reasoning/cache metadata must also remain billable.
        val single = assertNotNull(services.parseUsage(withUsage("{}", LLMServices.Usage(5, 7))))
        assertEquals(7, single.completionTokens)
    }

    @Test fun `a missing intermediate usage cannot silently become a complete charge`() {
        val total = accumulateUsage(accumulateUsage(null, null), LLMServices.Usage(30, 40))
        val parsed = assertNotNull(services.parseUsage(withUsage("{}", total)))
        assertEquals(2, parsed.apiCalls)
        assertFalse(parsed.complete)
        assertFalse(accumulateUsage(LLMServices.Usage(10, null), LLMServices.Usage(30, 40)).complete)
    }
}
