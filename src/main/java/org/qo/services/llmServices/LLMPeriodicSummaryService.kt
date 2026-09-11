package org.qo.services.llmServices

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicBoolean

@Service
class LLMPeriodicSummaryService(
	private val llmServices: LLMServices,
	private val chatHistoryService: LLMChatHistoryService,
	private val groupContextService: LLMGroupContextService,
	private val memberProfileService: LLMMemberProfileService,
) {
	private val enabled = readBoolean("LLM_PERIODIC_SUMMARY_ENABLED", true)
	private val maxGroupsPerRun = readInt("LLM_PERIODIC_SUMMARY_MAX_GROUPS", 1000).coerceIn(1, 10_000)
	private val batchSize = readInt("LLM_PERIODIC_SUMMARY_BATCH_MESSAGES", 200).coerceIn(10, 2000)
	private val minPendingMessages = readInt("LLM_PERIODIC_SUMMARY_MIN_MESSAGES", 40).coerceIn(1, batchSize)
	private val maxPendingWaitMs = readLong("LLM_PERIODIC_SUMMARY_MAX_WAIT_MS", 3_600_000L).coerceAtLeast(60_000L)
	private val maxBatchesPerGroup = readInt("LLM_PERIODIC_SUMMARY_MAX_BATCHES_PER_GROUP", 1).coerceIn(1, 100)
	private val running = AtomicBoolean(false)
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	@Scheduled(
		fixedDelayString = "\${LLM_PERIODIC_SUMMARY_INTERVAL_MS:600000}",
		initialDelayString = "\${LLM_PERIODIC_SUMMARY_INITIAL_DELAY_MS:120000}",
	)
	fun summarizeArchivedChats() {
		if (!enabled || !running.compareAndSet(false, true)) return
		scope.launch {
			try {
				runOnce()
			} catch (error: Exception) {
				println("[LLM] periodic summary run failed: ${error.message}")
			} finally {
				running.set(false)
			}
		}
	}

	internal suspend fun runOnce() {
		chatHistoryService.groupIdsForSummary(maxGroupsPerRun).forEach { groupId ->
			try {
				summarizeGroup(groupId)
			} catch (error: Exception) {
				println("[LLM] periodic summary failed for group $groupId: ${error.message}")
			}
		}
	}

	private suspend fun summarizeGroup(groupId: Long) {
		repeat(maxBatchesPerGroup) {
			val before = groupContextService.summaryCursor(groupId)
			val records = chatHistoryService.messagesForSummary(
				groupId = groupId,
				afterArchiveId = before.archiveId,
				fromTime = before.messageTime,
				limit = batchSize,
			)
			if (records.isEmpty()) return
			if (!shouldSummarizePeriodicBatch(records, batchSize, minPendingMessages, maxPendingWaitMs)) return
			groupContextService.updateFromArchive(groupId, records) { existingGroupSummary, pending ->
				val latestIdentityByUid = pending.asReversed()
					.mapNotNull { entry -> entry.uid.toLongOrNull()?.takeIf { it > 0 }?.let { it to entry.name } }
					.distinctBy { it.first }
				latestIdentityByUid.forEach { (qquid, name) ->
					memberProfileService.observeRequester(qquid, name, groupId)
				}
				val participantQqUids = latestIdentityByUid.map { it.first }
				val existingMemberSummaries = memberProfileService.profiles(participantQqUids, null)
					.mapNotNull { profile ->
						profile.fields.firstOrNull { it.key == LLMGroupChatPolicy.OBSERVED_SUMMARY_KEY }
							?.value
							?.let { profile.qqUid to it }
					}
					.toMap()
				val result = llmServices.summarizeGroupAndMemberProfiles(
					groupId,
					existingGroupSummary,
					existingMemberSummaries,
					pending,
				) ?: return@updateFromArchive null
				result.memberProfiles.forEach { member ->
					memberProfileService.upsertField(
						uid = member.qqUid,
						fieldKey = LLMGroupChatPolicy.OBSERVED_SUMMARY_KEY,
						value = member.summary,
						category = LLMGroupChatPolicy.OBSERVED_USER_PROFILE_CATEGORY,
						sourceName = "periodic-summary:group:$groupId",
					)
				}
				result.groupSummary
			}
			val after = groupContextService.summaryCursor(groupId)
			if (after.archiveId <= before.archiveId) {
				println("[LLM] periodic summary made no progress for group $groupId; retry deferred until the next run")
				return
			}
			if (records.size < batchSize) return
		}
	}

	@PreDestroy
	fun shutdown() {
		scope.cancel()
	}

	private companion object {
		fun readInt(name: String, defaultValue: Int): Int =
			System.getenv(name)?.trim()?.toIntOrNull() ?: defaultValue

		fun readLong(name: String, defaultValue: Long): Long =
			System.getenv(name)?.trim()?.toLongOrNull() ?: defaultValue

		fun readBoolean(name: String, defaultValue: Boolean): Boolean =
			System.getenv(name)?.trim()?.lowercase()?.toBooleanStrictOrNull() ?: defaultValue
	}
}

internal fun shouldSummarizePeriodicBatch(
	records: List<LLMChatHistoryRecord>,
	batchSize: Int,
	minPendingMessages: Int,
	maxPendingWaitMs: Long,
	now: Long = System.currentTimeMillis(),
): Boolean {
	if (records.isEmpty()) return false
	if (records.size >= batchSize || records.size >= minPendingMessages) return true
	val oldestCreatedAt = records.minOf { it.createdAt }
	return now >= oldestCreatedAt && now - oldestCreatedAt >= maxPendingWaitMs
}
