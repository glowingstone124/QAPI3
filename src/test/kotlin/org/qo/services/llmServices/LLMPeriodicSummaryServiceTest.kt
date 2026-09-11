package org.qo.services.llmServices

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LLMPeriodicSummaryServiceTest {
	@Test
	fun `waits for a useful partial batch`() {
		val records = records(count = 5, createdAt = 950_000L)

		assertFalse(
			shouldSummarizePeriodicBatch(
				records = records,
				batchSize = 200,
				minPendingMessages = 40,
				maxPendingWaitMs = 3_600_000L,
				now = 1_000_000L,
			)
		)
	}

	@Test
	fun `summarizes when the partial batch reaches forty messages`() {
		assertTrue(
			shouldSummarizePeriodicBatch(
				records = records(count = 40, createdAt = 950_000L),
				batchSize = 200,
				minPendingMessages = 40,
				maxPendingWaitMs = 3_600_000L,
				now = 1_000_000L,
			)
		)
	}

	@Test
	fun `summarizes a small partial batch after the maximum wait`() {
		assertTrue(
			shouldSummarizePeriodicBatch(
				records = records(count = 2, createdAt = 1_000_000L),
				batchSize = 200,
				minPendingMessages = 40,
				maxPendingWaitMs = 3_600_000L,
				now = 4_600_000L,
			)
		)
	}

	@Test
	fun `always processes a full catch-up batch`() {
		assertTrue(
			shouldSummarizePeriodicBatch(
				records = records(count = 200, createdAt = 999_999L),
				batchSize = 200,
				minPendingMessages = 40,
				maxPendingWaitMs = 3_600_000L,
				now = 1_000_000L,
			)
		)
	}

	private fun records(count: Int, createdAt: Long): List<LLMChatHistoryRecord> =
		(1..count).map { index ->
			LLMChatHistoryRecord(
				sourceId = "message-$index",
				groupId = 1L,
				uid = index.toLong(),
				name = "member-$index",
				content = "content-$index",
				time = createdAt,
				createdAt = createdAt,
				archiveId = index.toLong(),
			)
		}
}
