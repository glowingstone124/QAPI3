package org.qo.services.llmServices

import io.r2dbc.spi.Row
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.qo.datas.ReactiveDatabase
import org.springframework.stereotype.Service
import java.time.Instant

class SettingsConflict(message: String) : RuntimeException(message)
data class ResetGrantRequest(val requestId: String, val count: Int, val userId: Long? = null, val all: Boolean = false)
data class ResetGrantResult(val requestId: String, val recipients: Int, val count: Int)
data class ResetUseResult(val requestId: String, val restoredUnits: Int)
data class UsageTotals(val calls: Long, val inputTokens: Long, val outputTokens: Long, val chargedUnits: Long)
data class UsageEntry(val requestId: String, val createdAt: Long, val mode: String, val status: String,
    val inputTokens: Long, val outputTokens: Long, val chargedUnits: Long)
data class UsagePage(val items: List<UsageEntry>, val page: Int, val hasMore: Boolean)
data class ResetCardEvent(val kind: String, val count: Int, val createdAt: Long, val restoredUnits: Int)
data class AccountSettings(val userId: Long, val username: String, val hasAccount: Boolean, val quota: LLMQuotaView,
    val resetCards: Int, val activeRequests: Long, val statistics: UsageTotals, val resetHistory: List<ResetCardEvent>, val canGrantResetCards: Boolean)

@Service
class AccountSettingsService(
    private val db: ReactiveDatabase,
    private val store: SqlLLMQuotaStore,
    private val quota: LLMDailyQuotaService,
    private val providers: ReloadableLLMProvider,
) {
    private val schemaLock = Mutex()
    @Volatile private var ready = false
    fun canGrant(uid: Long) = uid in providers.current().adminUids
    private fun num(row: Row, key: String) = (row.get(key) as? Number)?.toLong() ?: 0L
    private fun validateId(id: String) { require(id.matches(Regex("[A-Za-z0-9_-]{8,80}"))) { "请求标识格式错误" } }
    suspend fun schema() {
        store.schema()
        if (ready) return
        schemaLock.withLock {
            if (ready) return
            for (sql in SCHEMA) db.execute(sql)
            ready = true
        }
    }
    private suspend fun lockAccount(uid: Long) {
        db.execute("INSERT INTO ai_quota_account (user_id,paid_credits) VALUES (?,0) ON DUPLICATE KEY UPDATE user_id=user_id", listOf(uid))
        db.one("SELECT user_id FROM ai_quota_account WHERE user_id=? FOR UPDATE", listOf(uid)) { true }
    }
    private suspend fun active(uid: Long) = db.one("SELECT COUNT(*) AS n FROM ai_quota_reservation WHERE user_id=? AND status IN ('reserved','pending')", listOf(uid)) { num(it,"n") } ?: 0L
    suspend fun balance(uid: Long): Int {
        schema()
        return db.one("SELECT cards FROM ai_reset_balance WHERE user_id=?", listOf(uid)) { num(it,"cards").toInt() } ?: 0
    }
    suspend fun settings(principal: LLMPrincipal): AccountSettings {
        schema()
        val q = quota.snapshot(principal.qqUid, principal.hasAccount)
        check(q.status != LLMQuotaStatus.UNAVAILABLE) { "额度服务暂不可用" }
        val totals = db.one("SELECT COUNT(*) AS calls,COALESCE(SUM(u.input_tokens),0) AS input_tokens,COALESCE(SUM(u.output_tokens),0) AS output_tokens,COALESCE(SUM(u.charged_units),0) AS charged_units FROM ai_quota_reservation r LEFT JOIN ai_usage u ON u.request_key=r.request_key WHERE r.user_id=?", listOf(principal.qqUid)) {
            UsageTotals(num(it,"calls"),num(it,"input_tokens"),num(it,"output_tokens"),num(it,"charged_units"))
        }!!
        val events = db.all("SELECT kind,delta,created_at,restored_units FROM ai_reset_ledger WHERE user_id=? ORDER BY id DESC LIMIT 20", listOf(principal.qqUid)) {
            ResetCardEvent(it.get("kind",String::class.java)!!,num(it,"delta").toInt(),num(it,"created_at"),num(it,"restored_units").toInt())
        }
        return AccountSettings(principal.qqUid,principal.displayName,principal.hasAccount,q.view,balance(principal.qqUid),active(principal.qqUid),totals,events,canGrant(principal.qqUid))
    }
    suspend fun history(uid: Long, page: Int): UsagePage {
        require(page in 0..10000) { "页码无效" }
        schema()
        val rows = db.all("SELECT r.request_key,r.created_at,r.mode,r.status,u.input_tokens,u.output_tokens,u.charged_units FROM ai_quota_reservation r LEFT JOIN ai_usage u ON u.request_key=r.request_key WHERE r.user_id=? ORDER BY r.created_at DESC,r.request_key DESC LIMIT 21 OFFSET ?", listOf(uid,page*20)) {
            UsageEntry(it.get("request_key",String::class.java)!!,num(it,"created_at"),it.get("mode",String::class.java)!!,it.get("status",String::class.java)!!,num(it,"input_tokens"),num(it,"output_tokens"),num(it,"charged_units"))
        }
        return UsagePage(rows.take(20),page,rows.size>20)
    }
    suspend fun grant(actor: Long, request: ResetGrantRequest): ResetGrantResult {
        require(canGrant(actor)) { "没有派发权限" }
        validateId(request.requestId)
        require(request.count in 1..100) { "每次可派发 1 至 100 张" }
        require(request.all == (request.userId == null) && (request.userId == null || request.userId > 0)) { "请选择指定用户或全体账户" }
        schema()
        val target = request.userId ?: 0L
        return db.inTransaction {
            db.execute("INSERT INTO ai_reset_grant (request_id,actor_id,target_id,card_count,recipients,status,created_at) VALUES (?,?,?,?,0,'pending',?) ON DUPLICATE KEY UPDATE request_id=request_id", listOf(request.requestId,actor,target,request.count,Instant.now().epochSecond))
            val old = db.one("SELECT * FROM ai_reset_grant WHERE request_id=? FOR UPDATE",listOf(request.requestId)) {
                require(num(it,"actor_id")==actor && num(it,"target_id")==target && num(it,"card_count")==request.count.toLong()) { "请求标识已用于另一项派发" }
                (it.get("status",String::class.java)=="complete") to num(it,"recipients").toInt()
            }
            if (old?.first == true) return@inTransaction ResetGrantResult(request.requestId,old.second,request.count)
            val candidates = if (request.all) db.all("SELECT uid AS user_id FROM users WHERE uid>0 UNION SELECT user_id FROM ai_quota_account WHERE user_id>0 ORDER BY user_id") { num(it,"user_id") }
            else {
                val exists = db.one("SELECT uid AS user_id FROM users WHERE uid=? UNION SELECT user_id FROM ai_quota_account WHERE user_id=?",listOf(target,target)) { true } == true
                require(exists) { "用户不存在，请填写账户 UID（QQ 号）" }
                listOf(target)
            }
            val now = Instant.now().epochSecond
            for (uid in candidates) {
                lockAccount(uid)
                db.execute("INSERT INTO ai_reset_balance (user_id,cards) VALUES (?,?) ON DUPLICATE KEY UPDATE cards=cards+VALUES(cards)",listOf(uid,request.count))
                db.execute("INSERT INTO ai_reset_ledger (user_id,reference_id,kind,delta,restored_units,created_at) VALUES (?,?,'grant',?,0,?)",listOf(uid,request.requestId,request.count,now))
            }
            db.execute("UPDATE ai_reset_grant SET recipients=?,status='complete' WHERE request_id=?",listOf(candidates.size,request.requestId))
            ResetGrantResult(request.requestId,candidates.size,request.count)
        }
    }
    suspend fun use(principal: LLMPrincipal, requestId: String): ResetUseResult {
        validateId(requestId)
        schema()
        return db.inTransaction {
            val uid = principal.qqUid
            lockAccount(uid)
            val prior = db.one("SELECT restored_units FROM ai_reset_ledger WHERE user_id=? AND reference_id=? AND kind='use'",listOf(uid,requestId)) { num(it,"restored_units").toInt() }
            if (prior != null) return@inTransaction ResetUseResult(requestId,prior)
            if (active(uid)>0) throw SettingsConflict("仍有请求执行中或等待结算，请稍后使用 reset 卡")
            if (balance(uid)<=0) throw SettingsConflict("没有可用的 reset 卡")
            val period = quota.period(Instant.now()).toString()
            val used = db.one("SELECT used FROM ai_weekly_usage WHERE user_id=? AND period=? FOR UPDATE",listOf(uid,period)) { num(it,"used").toInt() } ?: 0
            if (used<=0) throw SettingsConflict("本周用量为零，无需消耗 reset 卡")
            db.execute("UPDATE ai_reset_balance SET cards=cards-1 WHERE user_id=?",listOf(uid))
            db.execute("UPDATE ai_weekly_usage SET used=0 WHERE user_id=? AND period=?",listOf(uid,period))
            db.execute("INSERT INTO ai_reset_ledger (user_id,reference_id,kind,delta,restored_units,created_at) VALUES (?,?,'use',-1,?,?)",listOf(uid,requestId,used,Instant.now().epochSecond))
            ResetUseResult(requestId,used)
        }
    }
    companion object {
        val SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS ai_reset_balance (user_id BIGINT PRIMARY KEY,cards INT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS ai_reset_grant (request_id VARCHAR(80) PRIMARY KEY,actor_id BIGINT NOT NULL,target_id BIGINT NOT NULL,card_count INT NOT NULL,recipients INT NOT NULL,status VARCHAR(16) NOT NULL,created_at BIGINT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS ai_reset_ledger (id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NOT NULL,reference_id VARCHAR(80) NOT NULL,kind VARCHAR(16) NOT NULL,delta INT NOT NULL,restored_units INT NOT NULL,created_at BIGINT NOT NULL,UNIQUE(user_id,reference_id,kind))"
        )
    }
}
