package org.qo.services.llmServices

import io.r2dbc.spi.Row
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.qo.datas.ReactiveDatabase
import org.springframework.stereotype.Repository
import java.time.Instant

interface AccountSettingsRepository {
    suspend fun schema()
    suspend fun accountTargetExists(userId: Long): Boolean
    suspend fun balance(userId: Long): Int
    suspend fun loadSettings(userId: Long): AccountSettingsData
    suspend fun history(userId: Long, page: Int): UsagePage
    suspend fun grant(actor: Long, request: ResetGrantRequest): ResetGrantResult
    suspend fun redeem(userId: Long, requestId: String, period: String): ResetCardRedemption
}

data class AccountSettingsData(
    val resetCards: Int,
    val activeRequests: Long,
    val statistics: UsageTotals,
    val resetHistory: List<ResetCardEvent>,
)

enum class ResetCardRedemptionStatus {
    ALREADY_REDEEMED,
    ACTIVE_REQUESTS,
    NO_CARDS,
    NO_USAGE,
    REDEEMED,
}

data class ResetCardRedemption(
    val status: ResetCardRedemptionStatus,
    val restoredUnits: Int = 0,
)

@Repository
class SqlAccountSettingsRepository(
    private val db: ReactiveDatabase,
) : AccountSettingsRepository {
    private val schemaLock = Mutex()

    @Volatile
    private var ready = false

    override suspend fun schema() {
        if (ready) return
        schemaLock.withLock {
            if (ready) return
            for (sql in SCHEMA) db.execute(sql)
            ready = true
        }
    }

    override suspend fun accountTargetExists(userId: Long): Boolean {
        schema()
        return db.one(
            "SELECT uid AS user_id FROM users WHERE uid=? UNION SELECT user_id FROM ai_quota_account WHERE user_id=?",
            listOf(userId, userId),
        ) { true } == true
    }

    override suspend fun balance(userId: Long): Int {
        schema()
        return db.one(
            "SELECT cards FROM ai_reset_balance WHERE user_id=?",
            listOf(userId),
        ) { number(it, "cards").toInt() } ?: 0
    }

    override suspend fun loadSettings(userId: Long): AccountSettingsData {
        schema()
        val totals = db.one(
            """
            SELECT COUNT(*) AS calls,
                   COALESCE(SUM(u.input_tokens),0) AS input_tokens,
                   COALESCE(SUM(u.output_tokens),0) AS output_tokens,
                   COALESCE(SUM(u.charged_units),0) AS charged_units
            FROM ai_quota_reservation r
            LEFT JOIN ai_usage u ON u.request_key=r.request_key
            WHERE r.user_id=?
            """.trimIndent(),
            listOf(userId),
        ) {
            UsageTotals(
                number(it, "calls"),
                number(it, "input_tokens"),
                number(it, "output_tokens"),
                number(it, "charged_units"),
            )
        }!!
        val events = db.all(
            "SELECT kind,delta,created_at,restored_units FROM ai_reset_ledger WHERE user_id=? ORDER BY id DESC LIMIT 20",
            listOf(userId),
        ) {
            ResetCardEvent(
                it.get("kind", String::class.java)!!,
                number(it, "delta").toInt(),
                number(it, "created_at"),
                number(it, "restored_units").toInt(),
            )
        }
        val cards = balance(userId)
        val active = activeRequests(userId)
        return AccountSettingsData(cards, active, totals, events)
    }

    override suspend fun history(userId: Long, page: Int): UsagePage {
        schema()
        val rows = db.all(
            """
            SELECT r.request_key,r.created_at,r.mode,r.status,
                   u.input_tokens,u.output_tokens,u.charged_units
            FROM ai_quota_reservation r
            LEFT JOIN ai_usage u ON u.request_key=r.request_key
            WHERE r.user_id=?
            ORDER BY r.created_at DESC,r.request_key DESC
            LIMIT 21 OFFSET ?
            """.trimIndent(),
            listOf(userId, page * 20),
        ) {
            UsageEntry(
                it.get("request_key", String::class.java)!!,
                number(it, "created_at"),
                it.get("mode", String::class.java)!!,
                it.get("status", String::class.java)!!,
                number(it, "input_tokens"),
                number(it, "output_tokens"),
                number(it, "charged_units"),
            )
        }
        return UsagePage(rows.take(20), page, rows.size > 20)
    }

    override suspend fun grant(actor: Long, request: ResetGrantRequest): ResetGrantResult {
        schema()
        val target = request.userId ?: 0L
        return db.inTransaction {
            db.execute(
                """
                INSERT INTO ai_reset_grant
                    (request_id,actor_id,target_id,card_count,recipients,status,created_at)
                VALUES (?,?,?,?,0,'pending',?)
                ON DUPLICATE KEY UPDATE request_id=request_id
                """.trimIndent(),
                listOf(request.requestId, actor, target, request.count, Instant.now().epochSecond),
            )
            val old = db.one(
                "SELECT * FROM ai_reset_grant WHERE request_id=? FOR UPDATE",
                listOf(request.requestId),
            ) {
                require(
                    number(it, "actor_id") == actor &&
                        number(it, "target_id") == target &&
                        number(it, "card_count") == request.count.toLong()
                ) { "请求标识已用于另一项派发" }
                (it.get("status", String::class.java) == "complete") to number(it, "recipients").toInt()
            }
            if (old?.first == true) {
                return@inTransaction ResetGrantResult(request.requestId, old.second, request.count)
            }

            val candidates = if (request.all) {
                db.all(
                    "SELECT uid AS user_id FROM users WHERE uid>0 UNION SELECT user_id FROM ai_quota_account WHERE user_id>0 ORDER BY user_id",
                ) { number(it, "user_id") }
            } else {
                listOf(target)
            }
            val now = Instant.now().epochSecond
            for (uid in candidates) {
                lockAccount(uid)
                db.execute(
                    """
                    INSERT INTO ai_reset_balance (user_id,cards)
                    VALUES (?,?)
                    ON DUPLICATE KEY UPDATE cards=cards+VALUES(cards)
                    """.trimIndent(),
                    listOf(uid, request.count),
                )
                db.execute(
                    """
                    INSERT INTO ai_reset_ledger
                        (user_id,reference_id,kind,delta,restored_units,created_at)
                    VALUES (?,?,'grant',?,0,?)
                    """.trimIndent(),
                    listOf(uid, request.requestId, request.count, now),
                )
            }
            db.execute(
                "UPDATE ai_reset_grant SET recipients=?,status='complete' WHERE request_id=?",
                listOf(candidates.size, request.requestId),
            )
            ResetGrantResult(request.requestId, candidates.size, request.count)
        }
    }

    override suspend fun redeem(userId: Long, requestId: String, period: String): ResetCardRedemption {
        schema()
        return db.inTransaction {
            lockAccount(userId)
            val prior = db.one(
                """
                SELECT restored_units
                FROM ai_reset_ledger
                WHERE user_id=? AND reference_id=? AND kind='use'
                """.trimIndent(),
                listOf(userId, requestId),
            ) { number(it, "restored_units").toInt() }
            if (prior != null) {
                return@inTransaction ResetCardRedemption(
                    ResetCardRedemptionStatus.ALREADY_REDEEMED,
                    prior,
                )
            }

            if (activeRequests(userId) > 0) {
                return@inTransaction ResetCardRedemptionStatus.ACTIVE_REQUESTS.result()
            }
            if (balanceLocked(userId) <= 0) {
                return@inTransaction ResetCardRedemptionStatus.NO_CARDS.result()
            }
            val used = db.one(
                "SELECT used FROM ai_weekly_usage WHERE user_id=? AND period=? FOR UPDATE",
                listOf(userId, period),
            ) { number(it, "used").toInt() } ?: 0
            if (used <= 0) {
                return@inTransaction ResetCardRedemptionStatus.NO_USAGE.result()
            }

            db.execute(
                "UPDATE ai_reset_balance SET cards=cards-1 WHERE user_id=?",
                listOf(userId),
            )
            db.execute(
                "UPDATE ai_weekly_usage SET used=0 WHERE user_id=? AND period=?",
                listOf(userId, period),
            )
            db.execute(
                """
                INSERT INTO ai_reset_ledger
                    (user_id,reference_id,kind,delta,restored_units,created_at)
                VALUES (?,?,'use',-1,?,?)
                """.trimIndent(),
                listOf(userId, requestId, used, Instant.now().epochSecond),
            )
            ResetCardRedemption(ResetCardRedemptionStatus.REDEEMED, used)
        }
    }

    private suspend fun lockAccount(userId: Long) {
        db.execute(
            """
            INSERT INTO ai_quota_account (user_id,paid_credits)
            VALUES (?,0)
            ON DUPLICATE KEY UPDATE user_id=user_id
            """.trimIndent(),
            listOf(userId),
        )
        db.one(
            "SELECT user_id FROM ai_quota_account WHERE user_id=? FOR UPDATE",
            listOf(userId),
        ) { true }
    }

    private suspend fun activeRequests(userId: Long): Long = db.one(
        """
        SELECT COUNT(*) AS n
        FROM ai_quota_reservation
        WHERE user_id=? AND status IN ('reserved','pending')
        """.trimIndent(),
        listOf(userId),
    ) { number(it, "n") } ?: 0L

    private suspend fun balanceLocked(userId: Long): Int = db.one(
        "SELECT cards FROM ai_reset_balance WHERE user_id=?",
        listOf(userId),
    ) { number(it, "cards").toInt() } ?: 0

    private fun ResetCardRedemptionStatus.result() = ResetCardRedemption(this)

    private fun number(row: Row, key: String): Long = (row.get(key) as? Number)?.toLong() ?: 0L

    companion object {
        val SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS ai_reset_balance (user_id BIGINT PRIMARY KEY,cards INT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS ai_reset_grant (request_id VARCHAR(80) PRIMARY KEY,actor_id BIGINT NOT NULL,target_id BIGINT NOT NULL,card_count INT NOT NULL,recipients INT NOT NULL,status VARCHAR(16) NOT NULL,created_at BIGINT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS ai_reset_ledger (id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NOT NULL,reference_id VARCHAR(80) NOT NULL,kind VARCHAR(16) NOT NULL,delta INT NOT NULL,restored_units INT NOT NULL,created_at BIGINT NOT NULL,UNIQUE(user_id,reference_id,kind))",
        )
    }
}
