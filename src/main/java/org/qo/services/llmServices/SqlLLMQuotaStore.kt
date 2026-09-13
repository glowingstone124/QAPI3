package org.qo.services.llmServices

import io.r2dbc.spi.Row
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.qo.datas.ReactiveDatabase
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/** Durable account, reservations and payment grants share the same database transactions. */
@Component
class SqlLLMQuotaStore(private val db: ReactiveDatabase) : LLMQuotaStore {
    private val schemaLock = Mutex()
    @Volatile private var ready = false
    suspend fun schema() {
        if (ready) return
        schemaLock.withLock {
            if (ready) return
            for (sql in SCHEMA) db.execute(sql)
            ready = true
        }
    }
    private fun period(key: String) = key.substringAfter("llm:weekly:").substringBeforeLast(':')
    private fun uid(key: String) = key.substringAfterLast(':').toLong()
    private fun number(row: Row, key: String) = (row.get(key) as Number).toLong()
    private suspend fun account(uid: Long) {
        db.execute("INSERT INTO ai_quota_account (user_id, paid_credits) VALUES (?, 0) ON DUPLICATE KEY UPDATE user_id=user_id", listOf(uid))
    }
    private suspend fun week(uid: Long, period: String) {
        db.execute("INSERT INTO ai_weekly_usage (user_id, period, used) VALUES (?, ?, 0) ON DUPLICATE KEY UPDATE user_id=user_id", listOf(uid, period))
    }
    override suspend fun used(quotaKey: String): Int? {
        schema()
        return db.one("SELECT used FROM ai_weekly_usage WHERE user_id=? AND period=?", listOf(uid(quotaKey), period(quotaKey))) { number(it,"used").toInt() } ?: 0
    }
    override suspend fun balance(qqUid: Long): Int {
        schema()
        return db.one("SELECT paid_credits FROM ai_quota_account WHERE user_id=?", listOf(qqUid)) { number(it,"paid_credits").toInt() } ?: 0
    }
    override suspend fun reserve(quotaKey: String, requestKey: String, limit: Int, expiresAtEpochSeconds: Long): LLMQuotaStoreDecision? =
        reserveUnits(LLMPrincipal(uid(quotaKey), "", LLMSource.WEB, ""), quotaKey, requestKey, limit, expiresAtEpochSeconds, 1, "fast", "", "", LLMDailyQuotaService.COST_PER_UNIT)

    override suspend fun reserveUnits(principal: LLMPrincipal, quotaKey: String, requestKey: String, limit: Int,
        expiresAt: Long, units: Int, mode: String, provider: String, model: String, estimatedCost: BigDecimal): LLMQuotaStoreDecision? {
        schema()
        return db.inTransaction {
            val uid = principal.qqUid
            account(uid)
            // All account operations lock the account first, including payments.
            val paid = db.one("SELECT paid_credits FROM ai_quota_account WHERE user_id=? FOR UPDATE", listOf(uid)) { number(it,"paid_credits").toInt() }!!
            week(uid, period(quotaKey))
            val used = db.one("SELECT used FROM ai_weekly_usage WHERE user_id=? AND period=? FOR UPDATE", listOf(uid, period(quotaKey))) { number(it,"used").toInt() }!!
            if (db.one("SELECT request_key FROM ai_quota_reservation WHERE request_key=?", listOf(requestKey)) { true } == true)
                return@inTransaction LLMQuotaStoreDecision(LLMQuotaStatus.DUPLICATE, used, paid)
            val now = Instant.now().epochSecond
            val active = db.one("SELECT COUNT(*) AS n FROM ai_quota_reservation WHERE user_id=? AND status IN ('reserved','pending')", listOf(uid)) { number(it,"n") }!!
            val rpm = db.one("SELECT COUNT(*) AS n FROM ai_quota_reservation WHERE user_id=? AND created_at>?", listOf(uid, now-60)) { number(it,"n") }!!
            if (active >= (if (principal.hasAccount) 2 else 1) || rpm >= (if (principal.hasAccount) 6 else 3))
                return@inTransaction LLMQuotaStoreDecision(LLMQuotaStatus.RATE_LIMITED, used, paid)
            val month = Instant.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().withDayOfMonth(1).toString()
            db.execute("INSERT INTO ai_free_budget (period, actual_cost, reserved_cost) VALUES (?, 0, 0) ON DUPLICATE KEY UPDATE period=period", listOf(month))
            val budget = db.one("SELECT actual_cost, reserved_cost FROM ai_free_budget WHERE period=? FOR UPDATE", listOf(month)) { (it.get("actual_cost") as BigDecimal) + (it.get("reserved_cost") as BigDecimal) }!!
            val normalWeekly = minOf(units, (limit-used).coerceAtLeast(0))
            val gated = budget >= BigDecimal("95") || (mode == "thinking" && budget >= BigDecimal("90")) ||
                (mode == "thinking" && budget >= BigDecimal("80") && units>30)
            val affordableWeekly = if (estimatedCost.signum()==0) normalWeekly else
                ((BigDecimal("95")-budget).coerceAtLeast(BigDecimal.ZERO)*BigDecimal(units))
                    .divide(estimatedCost,0,java.math.RoundingMode.DOWN).min(BigDecimal(normalWeekly)).toInt()
            val weekly = if (gated) 0 else minOf(normalWeekly,affordableWeekly)
            val paidUnits = units-weekly
            val freeReserve = if (weekly == 0) BigDecimal.ZERO else estimatedCost.multiply(BigDecimal(weekly)).divide(BigDecimal(units),12,java.math.RoundingMode.DOWN)
            if (paid < paidUnits) return@inTransaction LLMQuotaStoreDecision(LLMQuotaStatus.EXCEEDED,used,paid)
            if (budget >= BigDecimal("80")) org.slf4j.LoggerFactory.getLogger(javaClass).warn("AI free monthly budget warning: {} CNY", budget)
            db.execute("UPDATE ai_weekly_usage SET used=used+? WHERE user_id=? AND period=?", listOf(weekly, uid, period(quotaKey)))
            db.execute("UPDATE ai_quota_account SET paid_credits=paid_credits-? WHERE user_id=?", listOf(paidUnits, uid))
            db.execute("UPDATE ai_free_budget SET reserved_cost=reserved_cost+? WHERE period=?", listOf(freeReserve, month))
            db.execute("INSERT INTO ai_quota_reservation (request_key,user_id,period,weekly_limit,weekly_units,paid_units,mode,provider,model,status,created_at,reset_at,budget_period,free_reserved) VALUES (?,?,?,?,?,?,?,?,?,'reserved',?,?,?,?)",
                listOf(requestKey,uid,period(quotaKey),limit,weekly,paidUnits,mode,provider,model,now,expiresAt,month,freeReserve))
            LLMQuotaStoreDecision(LLMQuotaStatus.ACCEPTED, used+weekly, paid-paidUnits)
        }
    }
    private data class Reservation(val uid: Long, val period: String, val limit: Int, val weekly: Int, val paid: Int,
        val status: String, val reset: Long, val month: String, val freeReserved: BigDecimal)
    private suspend fun locked(reservation: LLMQuotaReservation): Reservation? {
        db.one("SELECT user_id FROM ai_quota_account WHERE user_id=? FOR UPDATE", listOf(reservation.qqUid)) { true }
        return db.one("SELECT * FROM ai_quota_reservation WHERE request_key=? FOR UPDATE", listOf(reservation.requestKey)) {
            Reservation(number(it,"user_id"),it.get("period",String::class.java)!!,number(it,"weekly_limit").toInt(),number(it,"weekly_units").toInt(),number(it,"paid_units").toInt(),it.get("status",String::class.java)!!,number(it,"reset_at"),it.get("budget_period",String::class.java)!!,it.get("free_reserved") as BigDecimal)
        }
    }
    override suspend fun refund(reservation: LLMQuotaReservation): Int? = refundUsage(reservation,null)
    override suspend fun refundUsage(reservation: LLMQuotaReservation, usage: AiQuotaUsage?): Int? {
        schema()
        return db.inTransaction {
            val r = locked(reservation) ?: return@inTransaction null
            if (r.status=="reserved") {
                db.execute("UPDATE ai_weekly_usage SET used=used-? WHERE user_id=? AND period=?",listOf(r.weekly,r.uid,r.period))
                db.execute("UPDATE ai_quota_account SET paid_credits=paid_credits+? WHERE user_id=?",listOf(r.paid,r.uid))
                val failedFreeCost = if(usage==null || r.weekly==0) BigDecimal.ZERO else
                    usage.actualCostCny.multiply(BigDecimal(r.weekly)).divide(BigDecimal(r.weekly+r.paid),12,java.math.RoundingMode.DOWN)
                db.execute("UPDATE ai_free_budget SET reserved_cost=reserved_cost-?,actual_cost=actual_cost+? WHERE period=?",listOf(r.freeReserved,failedFreeCost,r.month))
                if(usage!=null) db.execute("INSERT INTO ai_usage (request_key,user_id,conversation_id,input_tokens,output_tokens,cached_tokens,reasoning_tokens,actual_cost,charged_units,weekly_units,paid_units,created_at) VALUES (?,?,?,?,?,?,?,?,0,0,0,?)",
                    listOf(reservation.requestKey,r.uid,usage.conversationId,usage.inputTokens,usage.outputTokens,usage.cachedTokens,usage.reasoningTokens,usage.actualCostCny,Instant.now().epochSecond))
                db.execute("UPDATE ai_quota_reservation SET status='refunded' WHERE request_key=?",listOf(reservation.requestKey))
                db.execute("INSERT INTO ai_credit_ledger (user_id,reference_id,delta,kind,created_at) VALUES (?,?,?,'refund',?)",listOf(r.uid,reservation.requestKey,r.paid,Instant.now().epochSecond))
            }
            used(reservation.quotaKey)
        }
    }
    override suspend fun settle(reservation: LLMQuotaReservation, usage: AiQuotaUsage): LLMQuotaView? {
        require(usage.chargedUnits == LLMDailyQuotaService.units(usage.actualCostCny))
        schema()
        return db.inTransaction {
            val r = locked(reservation) ?: return@inTransaction null
            if (r.status=="refunded") error("Cannot settle a refunded request")
            if (r.status=="reserved" || r.status=="pending") {
                val used = used(reservation.quotaKey)!!
                val paid = balance(r.uid)
                val availableWeekly = (r.limit-used+r.weekly).coerceAtLeast(0)
                require(usage.chargedUnits <= availableWeekly+paid+r.paid) { "Actual usage exceeds available balance; reconciliation required" }
                val freeBudget = db.one("SELECT actual_cost,reserved_cost FROM ai_free_budget WHERE period=? FOR UPDATE",listOf(r.month)) { (it.get("actual_cost") as BigDecimal)+(it.get("reserved_cost") as BigDecimal) }!!
                val room = (BigDecimal("95")-freeBudget+r.freeReserved).coerceAtLeast(BigDecimal.ZERO)
                val affordable = if(usage.actualCostCny.signum()==0) usage.chargedUnits else
                    (room*BigDecimal(usage.chargedUnits)).divide(usage.actualCostCny,0,java.math.RoundingMode.DOWN).min(BigDecimal(usage.chargedUnits)).toInt()
                val weekly = minOf(usage.chargedUnits, if(r.weekly==0) 0 else availableWeekly,affordable)
                val paidUnits = usage.chargedUnits-weekly
                require(paid+r.paid >= paidUnits) { "Actual usage exceeds available balance; reconciliation required" }
                val freeActual = if(weekly==0) BigDecimal.ZERO else usage.actualCostCny.multiply(BigDecimal(weekly)).divide(BigDecimal(usage.chargedUnits),12,java.math.RoundingMode.DOWN)
                db.execute("UPDATE ai_weekly_usage SET used=used+? WHERE user_id=? AND period=?",listOf(weekly-r.weekly,r.uid,r.period))
                db.execute("UPDATE ai_quota_account SET paid_credits=paid_credits+? WHERE user_id=?",listOf(r.paid-paidUnits,r.uid))
                db.execute("UPDATE ai_free_budget SET actual_cost=actual_cost+?,reserved_cost=reserved_cost-? WHERE period=?",listOf(freeActual,r.freeReserved,r.month))
                db.execute("UPDATE ai_quota_reservation SET status='settled' WHERE request_key=?",listOf(reservation.requestKey))
                db.execute("INSERT INTO ai_usage (request_key,user_id,conversation_id,input_tokens,output_tokens,cached_tokens,reasoning_tokens,actual_cost,charged_units,weekly_units,paid_units,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE weekly_units=VALUES(weekly_units),paid_units=VALUES(paid_units)",
                    listOf(reservation.requestKey,r.uid,usage.conversationId,usage.inputTokens,usage.outputTokens,usage.cachedTokens,usage.reasoningTokens,usage.actualCostCny,usage.chargedUnits,weekly,paidUnits,Instant.now().epochSecond))
                db.execute("INSERT INTO ai_credit_ledger (user_id,reference_id,delta,kind,created_at) VALUES (?,?,?,'usage',?)",listOf(r.uid,reservation.requestKey,-paidUnits,Instant.now().epochSecond))
            }
            val used = used(reservation.quotaKey)!!
            LLMQuotaView(r.limit,used,(r.limit-used).coerceAtLeast(0),r.reset,balance(r.uid),
                db.one("SELECT charged_units FROM ai_usage WHERE request_key=?",listOf(reservation.requestKey)) { number(it,"charged_units").toInt() })
        }
    }
    override suspend fun retain(reservation: LLMQuotaReservation, usage: AiQuotaUsage?) {
        schema()
        db.inTransaction {
            val r = locked(reservation) ?: return@inTransaction
            if (r.status != "reserved") return@inTransaction
            db.execute("UPDATE ai_quota_reservation SET status='pending' WHERE request_key=?",listOf(reservation.requestKey))
            if(usage != null) db.execute("INSERT INTO ai_usage (request_key,user_id,conversation_id,input_tokens,output_tokens,cached_tokens,reasoning_tokens,actual_cost,charged_units,weekly_units,paid_units,created_at) VALUES (?,?,?,?,?,?,?,?,?,0,0,?)",
                listOf(reservation.requestKey,r.uid,usage.conversationId,usage.inputTokens,usage.outputTokens,usage.cachedTokens,usage.reasoningTokens,usage.actualCostCny,usage.chargedUnits,Instant.now().epochSecond))
        }
    }
    override suspend fun reserveSubsidy(source: String, summary: LLMSummaryConfig, estimate: BigDecimal): String? {
        schema()
        return db.inTransaction {
            val month=Instant.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().withDayOfMonth(1).toString()
            db.execute("INSERT INTO ai_free_budget (period,actual_cost,reserved_cost) VALUES (?,0,0) ON DUPLICATE KEY UPDATE period=period",listOf(month))
            val spent=db.one("SELECT actual_cost,reserved_cost FROM ai_free_budget WHERE period=? FOR UPDATE",listOf(month)) { (it.get("actual_cost") as BigDecimal)+(it.get("reserved_cost") as BigDecimal) }!!
            if(spent+estimate>BigDecimal("95")) return@inTransaction null
            val id=java.util.UUID.randomUUID().toString()
            db.execute("UPDATE ai_free_budget SET reserved_cost=reserved_cost+? WHERE period=?",listOf(estimate,month))
            db.execute("INSERT INTO ai_system_usage (id,source,provider,model,budget_period,estimated_cost,status,created_at) VALUES (?,?,?,?,?,?,'reserved',?)",listOf(id,source,summary.providerName,summary.model,month,estimate,Instant.now().epochSecond))
            id
        }
    }
    override suspend fun settleSubsidy(id: String, cost: BigDecimal?) {
        schema()
        db.inTransaction {
            val row=db.one("SELECT * FROM ai_system_usage WHERE id=? FOR UPDATE",listOf(id)) {
                Triple(it.get("budget_period",String::class.java)!!,it.get("estimated_cost") as BigDecimal,it.get("status",String::class.java)!!)
            } ?: return@inTransaction
            if(row.third!="reserved") return@inTransaction
            if(cost==null) {
                db.execute("UPDATE ai_system_usage SET status='pending' WHERE id=?",listOf(id))
            } else {
                db.execute("UPDATE ai_free_budget SET reserved_cost=reserved_cost-?,actual_cost=actual_cost+? WHERE period=?",listOf(row.second,cost,row.first))
                db.execute("UPDATE ai_system_usage SET actual_cost=?,status='settled' WHERE id=?",listOf(cost,id))
            }
        }
    }
    suspend fun reconcileKnownUsage(userId: Long): Int {
        schema()
        val pending = db.all("SELECT r.*,u.input_tokens,u.output_tokens,u.cached_tokens,u.reasoning_tokens,u.actual_cost,u.charged_units,u.conversation_id FROM ai_quota_reservation r JOIN ai_usage u ON r.request_key=u.request_key WHERE r.user_id=? AND r.status='pending'",listOf(userId)) {
            val key=it.get("request_key",String::class.java)!!
            val period=it.get("period",String::class.java)!!
            val limit=number(it,"weekly_limit").toInt()
            val reset=number(it,"reset_at")
            val reservation=LLMQuotaReservation("llm:weekly:$period:$userId",key,reset,LLMQuotaView(limit,0,0,reset),userId)
            reservation to AiQuotaUsage(number(it,"input_tokens"),number(it,"output_tokens"),number(it,"cached_tokens"),
                (it.get("reasoning_tokens") as? Number)?.toLong(),it.get("actual_cost") as BigDecimal,number(it,"charged_units").toInt(),it.get("conversation_id",String::class.java))
        }
        var settled=0
        for((reservation,usage) in pending) {
            try { if(settle(reservation,usage)!=null) settled++ } catch(_: IllegalArgumentException) { /* More credits or operator review needed. */ }
        }
        return settled
    }
    companion object {
        val SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS ai_quota_account (user_id BIGINT PRIMARY KEY,paid_credits INT NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS ai_weekly_usage (user_id BIGINT NOT NULL,period VARCHAR(10) NOT NULL,used INT NOT NULL,PRIMARY KEY(user_id,period))",
            "CREATE TABLE IF NOT EXISTS ai_free_budget (period VARCHAR(10) PRIMARY KEY,actual_cost DECIMAL(20,12) NOT NULL,reserved_cost DECIMAL(20,12) NOT NULL)",
            "CREATE TABLE IF NOT EXISTS ai_quota_reservation (request_key VARCHAR(128) PRIMARY KEY,user_id BIGINT NOT NULL,period VARCHAR(10) NOT NULL,weekly_limit INT NOT NULL,weekly_units INT NOT NULL,paid_units INT NOT NULL,mode VARCHAR(16) NOT NULL,provider VARCHAR(64) NOT NULL,model VARCHAR(128) NOT NULL,status VARCHAR(16) NOT NULL,created_at BIGINT NOT NULL,reset_at BIGINT NOT NULL,budget_period VARCHAR(10) NOT NULL,free_reserved DECIMAL(20,12) NOT NULL)",
            "CREATE TABLE IF NOT EXISTS ai_system_usage (id VARCHAR(64) PRIMARY KEY,source VARCHAR(64) NOT NULL,provider VARCHAR(64) NOT NULL,model VARCHAR(128) NOT NULL,budget_period VARCHAR(10) NOT NULL,estimated_cost DECIMAL(20,12) NOT NULL,actual_cost DECIMAL(20,12),status VARCHAR(16) NOT NULL,created_at BIGINT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS ai_usage (request_key VARCHAR(128) PRIMARY KEY,user_id BIGINT NOT NULL,conversation_id VARCHAR(128),input_tokens BIGINT NOT NULL,output_tokens BIGINT NOT NULL,cached_tokens BIGINT NOT NULL,reasoning_tokens BIGINT,actual_cost DECIMAL(20,12) NOT NULL,charged_units INT NOT NULL,weekly_units INT NOT NULL,paid_units INT NOT NULL,created_at BIGINT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS ai_credit_ledger (id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NOT NULL,reference_id VARCHAR(128) NOT NULL,delta INT NOT NULL,kind VARCHAR(16) NOT NULL,created_at BIGINT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS ai_purchase_intent (id VARCHAR(64) PRIMARY KEY,user_id BIGINT NOT NULL,sku_id VARCHAR(64) NOT NULL,amount DECIMAL(10,2) NOT NULL,credits INT NOT NULL,status VARCHAR(16) NOT NULL,created_at BIGINT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS ai_afdian_pending_order (out_trade_no VARCHAR(64) PRIMARY KEY,status VARCHAR(16) NOT NULL,attempts INT NOT NULL,next_attempt_at BIGINT NOT NULL,last_error VARCHAR(64),created_at BIGINT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS ai_payment_order (id BIGINT AUTO_INCREMENT PRIMARY KEY,provider VARCHAR(32) NOT NULL,out_trade_no VARCHAR(64) NOT NULL,intent_id VARCHAR(64) NOT NULL UNIQUE,user_id BIGINT NOT NULL,amount DECIMAL(10,2) NOT NULL,credits INT NOT NULL,created_at BIGINT NOT NULL,UNIQUE(provider,out_trade_no))"
        )
    }
}
