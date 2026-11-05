package org.qo.services.llmServices

import io.r2dbc.spi.Row
import org.qo.datas.ReactiveDatabase
import org.qo.db.repository.SchemaInitializer
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/** Durable account, reservations and payment grants share the same database transactions. */
@Component
class SqlLLMQuotaStore(private val db: ReactiveDatabase) : LLMQuotaStore {
	private val schemaInitializer = SchemaInitializer()
	suspend fun schema() = schemaInitializer.ensure {
		for (sql in SCHEMA) db.execute(sql)
		val migrations = listOf(
			"ALTER TABLE ai_quota_reservation ADD COLUMN group_id BIGINT NULL",
			"ALTER TABLE ai_quota_reservation ADD COLUMN group_units INT NOT NULL DEFAULT 0",
			"ALTER TABLE ai_usage ADD COLUMN group_id BIGINT NULL",
			"ALTER TABLE ai_usage ADD COLUMN group_units INT NOT NULL DEFAULT 0",
		)
		for (sql in migrations) {
			runCatching { db.execute(sql) }
		}
	}

	private fun period(key: String) = key.substringAfter("llm:weekly:").substringBeforeLast(':')
	private fun uid(key: String) = key.substringAfterLast(':').toLong()
	private fun number(row: Row, key: String) = (row.get(key) as Number).toLong()
	private suspend fun account(uid: Long) {
		db.execute(
			"INSERT INTO ai_quota_account (user_id, paid_credits) VALUES (?, 0) ON DUPLICATE KEY UPDATE user_id=user_id",
			listOf(uid)
		)
	}

	private suspend fun week(uid: Long, period: String) {
		db.execute(
			"INSERT INTO ai_weekly_usage (user_id, period, used) VALUES (?, ?, 0) ON DUPLICATE KEY UPDATE user_id=user_id",
			listOf(uid, period)
		)
	}

	private suspend fun groupWeek(groupId: Long, period: String) {
		db.execute(
			"INSERT INTO ai_group_weekly_usage (group_id, period, used) VALUES (?, ?, 0) ON DUPLICATE KEY UPDATE group_id=group_id",
			listOf(groupId, period)
		)
	}

	override suspend fun used(quotaKey: String): Int? {
		schema()
		return db.one(
			"SELECT used FROM ai_weekly_usage WHERE user_id=? AND period=?",
			listOf(uid(quotaKey), period(quotaKey))
		) { number(it, "used").toInt() } ?: 0
	}

	override suspend fun groupUsed(groupId: Long, period: String): Int? {
		if (groupId <= 0) return 0
		schema()
		return db.one(
			"SELECT used FROM ai_group_weekly_usage WHERE group_id=? AND period=?",
			listOf(groupId, period)
		) { number(it, "used").toInt() } ?: 0
	}

	override suspend fun balance(qqUid: Long): Int {
		schema()
		return db.one("SELECT paid_credits FROM ai_quota_account WHERE user_id=?", listOf(qqUid)) {
			number(
				it,
				"paid_credits"
			).toInt()
		} ?: 0
	}

	override suspend fun deductQuota(
		qqUid: Long,
		period: String,
		limit: Int,
		amount: Int,
		referenceId: String,
		kind: String,
		groupId: Long?,
		groupLimit: Int,
	): CreditDeductionResult {
		require(amount > 0) { "Deduction amount must be positive" }
		schema()
		return db.inTransaction {
			account(qqUid)
			val paid = db.one("SELECT paid_credits FROM ai_quota_account WHERE user_id=? FOR UPDATE", listOf(qqUid)) {
				number(it, "paid_credits").toInt()
			} ?: 0

			val (groupUsed, availableGroup) = if (groupId != null && groupId > 0 && groupLimit > 0 && period.isNotBlank() && period != "all") {
				groupWeek(groupId, period)
				val u = db.one("SELECT used FROM ai_group_weekly_usage WHERE group_id=? AND period=? FOR UPDATE", listOf(groupId, period)) {
					number(it, "used").toInt()
				} ?: 0
				u to maxOf(0, groupLimit - u)
			} else {
				0 to 0
			}

			val groupDeduct = minOf(amount, availableGroup)
			val remAfterGroup = amount - groupDeduct

			val (used, availableWeekly) = if (limit > 0 && period.isNotBlank() && period != "all") {
				week(qqUid, period)
				val u = db.one("SELECT used FROM ai_weekly_usage WHERE user_id=? AND period=? FOR UPDATE", listOf(qqUid, period)) {
					number(it, "used").toInt()
				} ?: 0
				u to maxOf(0, limit - u)
			} else {
				0 to 0
			}

			val weeklyDeduct = minOf(remAfterGroup, availableWeekly)
			val paidDeduct = remAfterGroup - weeklyDeduct

			if (paid < paidDeduct) {
				val groupInfo = if (groupLimit > 0) "群共享可用额度：${availableGroup} 点，" else ""
				return@inTransaction CreditDeductionResult(
					success = false,
					remainingCredits = paid,
					weeklyDeducted = 0,
					paidDeducted = 0,
					groupDeducted = 0,
					error = "账户剩余额度不足（${groupInfo}剩余免费额度：${availableWeekly} 点，Paid Credits：${paid} 点，本次生成图片需要：${amount} 点）。请充值点数或等待下周免费额度重置。"
				)
			}

			if (groupDeduct > 0 && groupId != null) {
				db.execute(
					"UPDATE ai_group_weekly_usage SET used=used+? WHERE group_id=? AND period=?",
					listOf(groupDeduct, groupId, period)
				)
			}
			if (weeklyDeduct > 0) {
				db.execute(
					"UPDATE ai_weekly_usage SET used=used+? WHERE user_id=? AND period=?",
					listOf(weeklyDeduct, qqUid, period)
				)
			}
			if (paidDeduct > 0) {
				db.execute(
					"UPDATE ai_quota_account SET paid_credits=paid_credits-? WHERE user_id=?",
					listOf(paidDeduct, qqUid)
				)
				val now = Instant.now().epochSecond
				db.execute(
					"INSERT INTO ai_credit_ledger (user_id, reference_id, delta, kind, created_at) VALUES (?, ?, ?, ?, ?)",
					listOf(qqUid, referenceId, -paidDeduct, kind, now)
				)
			}
			CreditDeductionResult(
				success = true,
				remainingCredits = paid - paidDeduct,
				weeklyDeducted = weeklyDeduct,
				paidDeducted = paidDeduct,
				groupDeducted = groupDeduct,
			)
		}
	}

	override suspend fun refundQuota(
		qqUid: Long,
		period: String,
		weeklyAmount: Int,
		paidAmount: Int,
		referenceId: String,
		kind: String,
		groupId: Long?,
		groupAmount: Int,
	): Boolean {
		if (weeklyAmount <= 0 && paidAmount <= 0 && groupAmount <= 0) return true
		schema()
		return db.inTransaction {
			account(qqUid)
			if (groupAmount > 0 && groupId != null && groupId > 0 && period.isNotBlank() && period != "all") {
				db.execute(
					"UPDATE ai_group_weekly_usage SET used=GREATEST(0, used-?) WHERE group_id=? AND period=?",
					listOf(groupAmount, groupId, period)
				)
			}
			if (weeklyAmount > 0 && period.isNotBlank() && period != "all") {
				db.execute(
					"UPDATE ai_weekly_usage SET used=GREATEST(0, used-?) WHERE user_id=? AND period=?",
					listOf(weeklyAmount, qqUid, period)
				)
			}
			if (paidAmount > 0) {
				db.execute(
					"UPDATE ai_quota_account SET paid_credits=paid_credits+? WHERE user_id=?",
					listOf(paidAmount, qqUid)
				)
				val now = Instant.now().epochSecond
				db.execute(
					"INSERT INTO ai_credit_ledger (user_id, reference_id, delta, kind, created_at) VALUES (?, ?, ?, ?, ?)",
					listOf(qqUid, referenceId, paidAmount, kind, now)
				)
			}
			true
		}
	}

	override suspend fun deductCredits(qqUid: Long, amount: Int, referenceId: String, kind: String): CreditDeductionResult =
		deductQuota(qqUid, "all", 0, amount, referenceId, kind)

	override suspend fun refundCredits(qqUid: Long, amount: Int, referenceId: String, kind: String): Boolean =
		refundQuota(qqUid, "all", 0, amount, referenceId, kind)

	override suspend fun reserve(
		quotaKey: String,
		requestKey: String,
		limit: Int,
		expiresAtEpochSeconds: Long
	): LLMQuotaStoreDecision? =
		reserveUnits(
			LLMPrincipal(uid(quotaKey), "", LLMSource.WEB, ""),
			quotaKey,
			requestKey,
			limit,
			expiresAtEpochSeconds,
			1,
			"fast",
			"",
			"",
			LLMDailyQuotaService.COST_PER_UNIT,
			null,
			0
		)

	override suspend fun reserveUnits(
		principal: LLMPrincipal, quotaKey: String, requestKey: String, limit: Int,
		expiresAt: Long, units: Int, mode: String, provider: String, model: String, estimatedCost: BigDecimal,
		groupId: Long?, groupLimit: Int,
	): LLMQuotaStoreDecision? {
		schema()
		return db.inTransaction {
			val uid = principal.qqUid
			val p = period(quotaKey)
			account(uid)
			// All account operations lock the account first, including payments.
			val paid = db.one("SELECT paid_credits FROM ai_quota_account WHERE user_id=? FOR UPDATE", listOf(uid)) {
				number(
					it,
					"paid_credits"
				).toInt()
			}!!
			week(uid, p)
			val used = db.one(
				"SELECT used FROM ai_weekly_usage WHERE user_id=? AND period=? FOR UPDATE",
				listOf(uid, p)
			) { number(it, "used").toInt() }!!
			val groupUsed = if (groupId != null && groupId > 0 && groupLimit > 0) {
				groupWeek(groupId, p)
				db.one(
					"SELECT used FROM ai_group_weekly_usage WHERE group_id=? AND period=? FOR UPDATE",
					listOf(groupId, p)
				) { number(it, "used").toInt() } ?: 0
			} else {
				0
			}
			if (db.one(
					"SELECT request_key FROM ai_quota_reservation WHERE request_key=?",
					listOf(requestKey)
				) { true } == true
			)
				return@inTransaction LLMQuotaStoreDecision(
					LLMQuotaStatus.DUPLICATE,
					used,
					paid,
					groupUsed = if (groupId != null && groupId > 0 && groupLimit > 0) groupUsed else null,
					groupLimit = if (groupId != null && groupId > 0 && groupLimit > 0) groupLimit else null,
				)
			val now = Instant.now().epochSecond
			val active = db.one(
				"SELECT COUNT(*) AS n FROM ai_quota_reservation WHERE user_id=? AND status IN ('reserved','pending')",
				listOf(uid)
			) { number(it, "n") }!!
			if (active >= (if (principal.hasAccount) 10 else 5))
				return@inTransaction LLMQuotaStoreDecision(
					LLMQuotaStatus.RATE_LIMITED,
					used,
					paid,
					groupUsed = if (groupId != null && groupId > 0 && groupLimit > 0) groupUsed else null,
					groupLimit = if (groupId != null && groupId > 0 && groupLimit > 0) groupLimit else null,
				)
			val month = Instant.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().withDayOfMonth(1).toString()
			db.execute(
				"INSERT INTO ai_free_budget (period, actual_cost, reserved_cost) VALUES (?, 0, 0) ON DUPLICATE KEY UPDATE period=period",
				listOf(month)
			)
			val (groupUnits, weeklyUnits, paidUnits) = if (groupId != null && groupId > 0 && groupLimit > 0) {
				val availableGroup = (groupLimit - groupUsed).coerceAtLeast(0)
				val gUnits = minOf(units, availableGroup)
				val remAfterGroup = units - gUnits
				val wUnits = minOf(remAfterGroup, (limit - used).coerceAtLeast(0))
				val pUnits = remAfterGroup - wUnits
				Triple(gUnits, wUnits, pUnits)
			} else {
				val wUnits = minOf(units, (limit - used).coerceAtLeast(0))
				val pUnits = units - wUnits
				Triple(0, wUnits, pUnits)
			}
			val freeUnits = groupUnits + weeklyUnits
			val freeReserve = if (freeUnits == 0) BigDecimal.ZERO else estimatedCost.multiply(BigDecimal(freeUnits))
				.divide(BigDecimal(units), 12, java.math.RoundingMode.DOWN)
			if (paid < paidUnits) return@inTransaction LLMQuotaStoreDecision(
				LLMQuotaStatus.EXCEEDED,
				used,
				paid,
				groupUsed = if (groupId != null && groupId > 0 && groupLimit > 0) groupUsed else null,
				groupLimit = if (groupId != null && groupId > 0 && groupLimit > 0) groupLimit else null,
			)
			if (groupUnits > 0 && groupId != null) {
				db.execute(
					"UPDATE ai_group_weekly_usage SET used=used+? WHERE group_id=? AND period=?",
					listOf(groupUnits, groupId, p)
				)
			}
			if (weeklyUnits > 0) {
				db.execute(
					"UPDATE ai_weekly_usage SET used=used+? WHERE user_id=? AND period=?",
					listOf(weeklyUnits, uid, p)
				)
			}
			if (paidUnits > 0) {
				db.execute(
					"UPDATE ai_quota_account SET paid_credits=paid_credits-? WHERE user_id=?",
					listOf(paidUnits, uid)
				)
			}
			if (freeReserve.signum() > 0) {
				db.execute(
					"UPDATE ai_free_budget SET reserved_cost=reserved_cost+? WHERE period=?",
					listOf(freeReserve, month)
				)
			}
			db.execute(
				"INSERT INTO ai_quota_reservation (request_key,user_id,period,weekly_limit,weekly_units,paid_units,mode,provider,model,status,created_at,reset_at,budget_period,free_reserved,group_id,group_units) VALUES (?,?,?,?,?,?,?,?,?,'reserved',?,?,?,?,?,?)",
				listOf(
					requestKey,
					uid,
					p,
					limit,
					weeklyUnits,
					paidUnits,
					mode,
					provider,
					model,
					now,
					expiresAt,
					month,
					freeReserve,
					groupId,
					groupUnits,
				)
			)
			LLMQuotaStoreDecision(
				LLMQuotaStatus.ACCEPTED,
				used = used + weeklyUnits,
				paidCredits = paid - paidUnits,
				groupUsed = if (groupId != null && groupId > 0 && groupLimit > 0) groupUsed + groupUnits else null,
				groupLimit = if (groupId != null && groupId > 0 && groupLimit > 0) groupLimit else null,
			)
		}
	}

	private data class Reservation(
		val uid: Long, val period: String, val limit: Int, val weekly: Int, val paid: Int,
		val status: String, val reset: Long, val month: String, val freeReserved: BigDecimal,
		val groupId: Long?, val groupUnits: Int,
	)

	private data class ZombieReservation(
		val requestKey: String,
		val period: String,
		val weekly: Int,
		val paid: Int,
		val month: String,
		val freeReserved: BigDecimal,
		val groupId: Long?,
		val groupUnits: Int,
	)

	private suspend fun locked(reservation: LLMQuotaReservation): Reservation? {
		db.one("SELECT user_id FROM ai_quota_account WHERE user_id=? FOR UPDATE", listOf(reservation.qqUid)) { true }
		return db.one(
			"SELECT * FROM ai_quota_reservation WHERE request_key=? FOR UPDATE",
			listOf(reservation.requestKey)
		) {
			Reservation(
				number(it, "user_id"),
				it.get("period", String::class.java)!!,
				number(it, "weekly_limit").toInt(),
				number(it, "weekly_units").toInt(),
				number(it, "paid_units").toInt(),
				it.get("status", String::class.java)!!,
				number(it, "reset_at"),
				it.get("budget_period", String::class.java)!!,
				it.get("free_reserved") as BigDecimal,
				(it.get("group_id") as? Number)?.toLong(),
				(it.get("group_units") as? Number)?.toInt() ?: 0,
			)
		}
	}

	override suspend fun refund(reservation: LLMQuotaReservation): Int? = refundUsage(reservation, null)
	override suspend fun refundUsage(reservation: LLMQuotaReservation, usage: AiQuotaUsage?): Int? {
		schema()
		return db.inTransaction {
			val r = locked(reservation) ?: return@inTransaction null
			if (r.status == "reserved") {
				if (r.groupId != null && r.groupUnits > 0) {
					db.execute(
						"UPDATE ai_group_weekly_usage SET used=GREATEST(0, used-?) WHERE group_id=? AND period=?",
						listOf(r.groupUnits, r.groupId, r.period)
					)
				}
				if (r.weekly > 0) {
					db.execute(
						"UPDATE ai_weekly_usage SET used=used-? WHERE user_id=? AND period=?",
						listOf(r.weekly, r.uid, r.period)
					)
				}
				if (r.paid > 0) {
					db.execute(
						"UPDATE ai_quota_account SET paid_credits=paid_credits+? WHERE user_id=?",
						listOf(r.paid, r.uid)
					)
				}
				val freeUnits = r.groupUnits + r.weekly
				val failedFreeCost = if (usage == null || freeUnits == 0) BigDecimal.ZERO else
					usage.actualCostCny.multiply(BigDecimal(freeUnits))
						.divide(BigDecimal(freeUnits + r.paid), 12, java.math.RoundingMode.DOWN)
				db.execute(
					"UPDATE ai_free_budget SET reserved_cost=reserved_cost-?,actual_cost=actual_cost+? WHERE period=?",
					listOf(r.freeReserved, failedFreeCost, r.month)
				)
				if (usage != null) db.execute(
					"INSERT INTO ai_usage (request_key,user_id,conversation_id,input_tokens,output_tokens,cached_tokens,reasoning_tokens,actual_cost,charged_units,weekly_units,paid_units,created_at,group_id,group_units) VALUES (?,?,?,?,?,?,?,?,0,0,0,?,?,0)",
					listOf(
						reservation.requestKey,
						r.uid,
						usage.conversationId,
						usage.inputTokens,
						usage.outputTokens,
						usage.cachedTokens,
						usage.reasoningTokens,
						usage.actualCostCny,
						Instant.now().epochSecond,
						r.groupId,
					)
				)
				db.execute(
					"UPDATE ai_quota_reservation SET status='refunded' WHERE request_key=?",
					listOf(reservation.requestKey)
				)
				if (r.paid > 0) {
					db.execute(
						"INSERT INTO ai_credit_ledger (user_id,reference_id,delta,kind,created_at) VALUES (?,?,?,'refund',?)",
						listOf(r.uid, reservation.requestKey, r.paid, Instant.now().epochSecond)
					)
				}
			}
			used(reservation.quotaKey)
		}
	}

	override suspend fun settle(reservation: LLMQuotaReservation, usage: AiQuotaUsage): LLMQuotaView? =
		settle(reservation, usage, reservation.view.groupLimit ?: 0)

	override suspend fun settle(reservation: LLMQuotaReservation, usage: AiQuotaUsage, groupLimit: Int): LLMQuotaView? {
		require(usage.chargedUnits == LLMDailyQuotaService.units(usage.actualCostCny))
		schema()
		return db.inTransaction {
			val r = locked(reservation) ?: return@inTransaction null
			if (r.status == "refunded") error("Cannot settle a refunded request")
			if (r.status == "reserved" || r.status == "pending") {
				val totalUnits = usage.chargedUnits
				val (newGroupUnits, remAfterGroup) = if (r.groupId != null && r.groupId > 0 && groupLimit > 0) {
					groupWeek(r.groupId, r.period)
					val currentGroupUsed = db.one(
						"SELECT used FROM ai_group_weekly_usage WHERE group_id=? AND period=? FOR UPDATE",
						listOf(r.groupId, r.period)
					) { number(it, "used").toInt() } ?: 0
					val availableGroup = (groupLimit - currentGroupUsed + r.groupUnits).coerceAtLeast(0)
					val gUnits = minOf(totalUnits, availableGroup)
					gUnits to (totalUnits - gUnits)
				} else {
					0 to totalUnits
				}

				val currentUsed = used(reservation.quotaKey)!!
				val paid = balance(r.uid)
				val availableWeekly = (r.limit - currentUsed + r.weekly).coerceAtLeast(0)

				// An admitted turn completes even when its real cost exceeds the balance.
				// Use available group, available weekly units and Credits first, then overdraw weekly units.
				val normalWeekly = minOf(remAfterGroup, availableWeekly)
				val remAfterWeekly = remAfterGroup - normalWeekly
				val newPaidUnits = minOf(remAfterWeekly, paid + r.paid)
				val newWeeklyUnits = remAfterGroup - newPaidUnits

				val freeUnits = newGroupUnits + newWeeklyUnits
				val freeActual = if (freeUnits == 0) BigDecimal.ZERO else usage.actualCostCny.multiply(BigDecimal(freeUnits))
					.divide(BigDecimal(usage.chargedUnits), 12, java.math.RoundingMode.DOWN)

				if (r.groupId != null && r.groupId > 0) {
					val groupDelta = newGroupUnits - r.groupUnits
					if (groupDelta != 0) {
						db.execute(
							"UPDATE ai_group_weekly_usage SET used=used+? WHERE group_id=? AND period=?",
							listOf(groupDelta, r.groupId, r.period)
						)
					}
				}
				val weeklyDelta = newWeeklyUnits - r.weekly
				if (weeklyDelta != 0) {
					db.execute(
						"UPDATE ai_weekly_usage SET used=used+? WHERE user_id=? AND period=?",
						listOf(weeklyDelta, r.uid, r.period)
					)
				}
				val paidDelta = r.paid - newPaidUnits
				if (paidDelta != 0) {
					db.execute(
						"UPDATE ai_quota_account SET paid_credits=paid_credits+? WHERE user_id=?",
						listOf(paidDelta, r.uid)
					)
				}
				db.execute(
					"UPDATE ai_free_budget SET actual_cost=actual_cost+?,reserved_cost=reserved_cost-? WHERE period=?",
					listOf(freeActual, r.freeReserved, r.month)
				)
				db.execute(
					"UPDATE ai_quota_reservation SET status='settled' WHERE request_key=?",
					listOf(reservation.requestKey)
				)
				db.execute(
					"""
					INSERT INTO ai_usage (request_key,user_id,conversation_id,input_tokens,output_tokens,cached_tokens,reasoning_tokens,actual_cost,charged_units,weekly_units,paid_units,created_at,group_id,group_units)
					VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
					ON DUPLICATE KEY UPDATE weekly_units=VALUES(weekly_units),paid_units=VALUES(paid_units),group_id=VALUES(group_id),group_units=VALUES(group_units)
					""".trimIndent(),
					listOf(
						reservation.requestKey,
						r.uid,
						usage.conversationId,
						usage.inputTokens,
						usage.outputTokens,
						usage.cachedTokens,
						usage.reasoningTokens,
						usage.actualCostCny,
						usage.chargedUnits,
						newWeeklyUnits,
						newPaidUnits,
						Instant.now().epochSecond,
						r.groupId,
						newGroupUnits,
					)
				)
				if (newPaidUnits > 0) {
					db.execute(
						"INSERT INTO ai_credit_ledger (user_id,reference_id,delta,kind,created_at) VALUES (?,?,?,'usage',?)",
						listOf(r.uid, reservation.requestKey, -newPaidUnits, Instant.now().epochSecond)
					)
				}
			}

			val finalUsed = used(reservation.quotaKey)!!
			val finalGroupUsed = if (r.groupId != null && r.groupId > 0 && groupLimit > 0) {
				db.one(
					"SELECT used FROM ai_group_weekly_usage WHERE group_id=? AND period=?",
					listOf(r.groupId, r.period)
				) { number(it, "used").toInt() } ?: 0
			} else null
			val finalGroupRemaining = if (finalGroupUsed != null) {
				(groupLimit - finalGroupUsed).coerceAtLeast(0)
			} else null

			val chargedUnits = db.one(
				"SELECT charged_units FROM ai_usage WHERE request_key=?",
				listOf(reservation.requestKey)
			) { number(it, "charged_units").toInt() }
			val groupChargedUnits = if (r.groupId != null && r.groupId > 0 && groupLimit > 0) {
				db.one(
					"SELECT group_units FROM ai_usage WHERE request_key=?",
					listOf(reservation.requestKey)
				) { number(it, "group_units").toInt() }
			} else null

			LLMQuotaView(
				limit = r.limit,
				used = finalUsed,
				remaining = r.limit - finalUsed,
				resetAtEpochSeconds = r.reset,
				paidCredits = balance(r.uid),
				chargedUnits = chargedUnits,
				groupLimit = if (r.groupId != null && r.groupId > 0 && groupLimit > 0) groupLimit else null,
				groupUsed = finalGroupUsed,
				groupRemaining = finalGroupRemaining,
				groupChargedUnits = groupChargedUnits,
			)
		}
	}

	override suspend fun retain(reservation: LLMQuotaReservation, usage: AiQuotaUsage?) {
		schema()
		db.inTransaction {
			val r = locked(reservation) ?: return@inTransaction
			if (r.status != "reserved") return@inTransaction
			db.execute(
				"UPDATE ai_quota_reservation SET status='pending' WHERE request_key=?",
				listOf(reservation.requestKey)
			)
			if (usage != null) db.execute(
				"INSERT INTO ai_usage (request_key,user_id,conversation_id,input_tokens,output_tokens,cached_tokens,reasoning_tokens,actual_cost,charged_units,weekly_units,paid_units,created_at,group_id,group_units) VALUES (?,?,?,?,?,?,?,?,?,0,0,?,?,0)",
				listOf(
					reservation.requestKey,
					r.uid,
					usage.conversationId,
					usage.inputTokens,
					usage.outputTokens,
					usage.cachedTokens,
					usage.reasoningTokens,
					usage.actualCostCny,
					usage.chargedUnits,
					Instant.now().epochSecond,
					r.groupId,
				)
			)
		}
	}

	/**
	 * Cancels pending reservations that have no usage record and have outlived the
	 * upstream request timeout. A missing usage record is never cancelled before
	 * this grace period because the provider may still have accepted the request.
	 */
	suspend fun cancelZombiePending(userId: Long, now: Long = Instant.now().epochSecond): Int {
		schema()
		val cutoff = now - ZOMBIE_PENDING_TIMEOUT_SECONDS
		return db.inTransaction {
			account(userId)
			db.one("SELECT user_id FROM ai_quota_account WHERE user_id=? FOR UPDATE", listOf(userId)) { true }
			val candidates = db.all(
				"""
				SELECT request_key,period,weekly_units,paid_units,budget_period,free_reserved,group_id,group_units
				FROM ai_quota_reservation
				WHERE user_id=? AND status='pending' AND created_at<=?
				  AND NOT EXISTS (
					SELECT 1 FROM ai_usage WHERE ai_usage.request_key=ai_quota_reservation.request_key
				  )
				ORDER BY created_at
				""".trimIndent(),
				listOf(userId, cutoff)
			) {
				ZombieReservation(
					it.get("request_key", String::class.java)!!,
					it.get("period", String::class.java)!!,
					number(it, "weekly_units").toInt(),
					number(it, "paid_units").toInt(),
					it.get("budget_period", String::class.java)!!,
					it.get("free_reserved") as BigDecimal,
					(it.get("group_id") as? Number)?.toLong(),
					(it.get("group_units") as? Number)?.toInt() ?: 0,
				)
			}
			var cancelled = 0
			for (candidate in candidates) {
				val stillPending = db.one(
					"SELECT status FROM ai_quota_reservation WHERE request_key=? FOR UPDATE",
					listOf(candidate.requestKey)
				) { it.get("status", String::class.java) == "pending" } == true
				if (!stillPending || db.one(
						"SELECT request_key FROM ai_usage WHERE request_key=?",
						listOf(candidate.requestKey)
					) { true } == true
				) continue
				if (candidate.groupId != null && candidate.groupUnits > 0) {
					db.execute(
						"UPDATE ai_group_weekly_usage SET used=GREATEST(0, used-?) WHERE group_id=? AND period=?",
						listOf(candidate.groupUnits, candidate.groupId, candidate.period)
					)
				}
				if (candidate.weekly > 0) {
					db.execute(
						"UPDATE ai_weekly_usage SET used=used-? WHERE user_id=? AND period=?",
						listOf(candidate.weekly, userId, candidate.period)
					)
				}
				if (candidate.paid > 0) {
					db.execute(
						"UPDATE ai_quota_account SET paid_credits=paid_credits+? WHERE user_id=?",
						listOf(candidate.paid, userId)
					)
				}
				db.execute(
					"UPDATE ai_free_budget SET reserved_cost=reserved_cost-? WHERE period=?",
					listOf(candidate.freeReserved, candidate.month)
				)
				db.execute(
					"UPDATE ai_quota_reservation SET status='refunded' WHERE request_key=?",
					listOf(candidate.requestKey)
				)
				if (candidate.paid > 0) {
					db.execute(
						"INSERT INTO ai_credit_ledger (user_id,reference_id,delta,kind,created_at) VALUES (?,?,?,'refund',?)",
						listOf(userId, candidate.requestKey, candidate.paid, now)
					)
				}
				cancelled++
			}
			cancelled
		}
	}

	override suspend fun reserveSubsidy(source: String, summary: LLMSummaryConfig, estimate: BigDecimal): String? {
		schema()
		return db.inTransaction {
			val month = Instant.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().withDayOfMonth(1).toString()
			db.execute(
				"INSERT INTO ai_free_budget (period,actual_cost,reserved_cost) VALUES (?,0,0) ON DUPLICATE KEY UPDATE period=period",
				listOf(month)
			)
			val id = java.util.UUID.randomUUID().toString()
			db.execute(
				"UPDATE ai_free_budget SET reserved_cost=reserved_cost+? WHERE period=?",
				listOf(estimate, month)
			)
			db.execute(
				"INSERT INTO ai_system_usage (id,source,provider,model,budget_period,estimated_cost,status,created_at) VALUES (?,?,?,?,?,?,'reserved',?)",
				listOf(id, source, summary.providerName, summary.model, month, estimate, Instant.now().epochSecond)
			)
			id
		}
	}

	override suspend fun settleSubsidy(id: String, cost: BigDecimal?) {
		schema()
		db.inTransaction {
			val row = db.one("SELECT * FROM ai_system_usage WHERE id=? FOR UPDATE", listOf(id)) {
				Triple(
					it.get("budget_period", String::class.java)!!,
					it.get("estimated_cost") as BigDecimal,
					it.get("status", String::class.java)!!
				)
			} ?: return@inTransaction
			if (row.third != "reserved") return@inTransaction
			if (cost == null) {
				db.execute("UPDATE ai_system_usage SET status='pending' WHERE id=?", listOf(id))
			} else {
				db.execute(
					"UPDATE ai_free_budget SET reserved_cost=reserved_cost-?,actual_cost=actual_cost+? WHERE period=?",
					listOf(row.second, cost, row.first)
				)
				db.execute("UPDATE ai_system_usage SET actual_cost=?,status='settled' WHERE id=?", listOf(cost, id))
			}
		}
	}

	suspend fun reconcileKnownUsage(userId: Long): Int {
		schema()
		val pending = db.all(
			"SELECT r.*,u.input_tokens,u.output_tokens,u.cached_tokens,u.reasoning_tokens,u.actual_cost,u.charged_units,u.conversation_id FROM ai_quota_reservation r JOIN ai_usage u ON r.request_key=u.request_key WHERE r.user_id=? AND r.status='pending'",
			listOf(userId)
		) {
			val key = it.get("request_key", String::class.java)!!
			val period = it.get("period", String::class.java)!!
			val limit = number(it, "weekly_limit").toInt()
			val reset = number(it, "reset_at")
			val groupId = (it.get("group_id") as? Number)?.toLong()
			val reservation =
				LLMQuotaReservation("llm:weekly:$period:$userId", key, reset, LLMQuotaView(limit, 0, 0, reset), userId, groupId = groupId)
			reservation to AiQuotaUsage(
				number(it, "input_tokens"),
				number(it, "output_tokens"),
				number(it, "cached_tokens"),
				(it.get("reasoning_tokens") as? Number)?.toLong(),
				it.get("actual_cost") as BigDecimal,
				number(it, "charged_units").toInt(),
				it.get("conversation_id", String::class.java)
			)
		}
		var settled = 0
		for ((reservation, usage) in pending) {
			try {
				if (settle(reservation, usage) != null) settled++
			} catch (_: IllegalArgumentException) { /* More credits or operator review needed. */
			}
		}
		return settled
	}

	companion object {
		const val ZOMBIE_PENDING_TIMEOUT_SECONDS = org.qo.db.repository.LlmQuotaSchema.ZOMBIE_PENDING_TIMEOUT_SECONDS
		val SCHEMA get() = org.qo.db.repository.LlmQuotaSchema.SCHEMA
	}
}
