package org.qo.services.llmServices

import org.springframework.stereotype.Service
import java.time.Instant

class SettingsConflict(message: String) : RuntimeException(message)
data class ResetGrantRequest(val requestId: String, val count: Int, val userId: Long? = null, val all: Boolean = false)
data class ResetGrantResult(val requestId: String, val recipients: Int, val count: Int)
data class ResetUseResult(val requestId: String, val restoredUnits: Int)
data class UsageTotals(val calls: Long, val inputTokens: Long, val outputTokens: Long, val chargedUnits: Long)
data class UsageEntry(
    val requestId: String,
    val createdAt: Long,
    val mode: String,
    val status: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val chargedUnits: Long,
)
data class UsagePage(val items: List<UsageEntry>, val page: Int, val hasMore: Boolean)
data class ResetCardEvent(val kind: String, val count: Int, val createdAt: Long, val restoredUnits: Int)
data class AccountSettings(
    val userId: Long,
    val username: String,
    val hasAccount: Boolean,
    val quota: LLMQuotaView,
    val resetCards: Int,
    val activeRequests: Long,
    val statistics: UsageTotals,
    val resetHistory: List<ResetCardEvent>,
    val canGrantResetCards: Boolean,
)

@Service
class AccountSettingsService(
    private val repository: AccountSettingsRepository,
    private val store: SqlLLMQuotaStore,
    private val quota: LLMDailyQuotaService,
    private val providers: ReloadableLLMProvider,
) {
    fun canGrant(uid: Long) = uid in providers.current().adminUids

    private fun validateId(id: String) {
        require(id.matches(Regex("[A-Za-z0-9_-]{8,80}"))) { "请求标识格式错误" }
    }

    suspend fun schema() {
        store.schema()
        repository.schema()
    }

    suspend fun balance(uid: Long): Int = repository.balance(uid)

    suspend fun settings(principal: LLMPrincipal): AccountSettings {
        store.reconcileKnownUsage(principal.qqUid)
        store.cancelZombiePending(principal.qqUid)
        val q = quota.snapshot(principal.qqUid, principal.hasAccount)
        check(q.status != LLMQuotaStatus.UNAVAILABLE) { "额度服务暂不可用" }
        val data = repository.loadSettings(principal.qqUid)
        return AccountSettings(
            principal.qqUid,
            principal.displayName,
            principal.hasAccount,
            q.view,
            data.resetCards,
            data.activeRequests,
            data.statistics,
            data.resetHistory,
            canGrant(principal.qqUid),
        )
    }

    suspend fun history(uid: Long, page: Int): UsagePage {
        require(page in 0..10000) { "页码无效" }
        schema()
        return repository.history(uid, page)
    }

    suspend fun grant(actor: Long, request: ResetGrantRequest): ResetGrantResult {
        require(canGrant(actor)) { "没有派发权限" }
        validateId(request.requestId)
        require(request.count in 1..100) { "每次可派发 1 至 100 张" }
        require(
            request.all == (request.userId == null) &&
                (request.userId == null || request.userId > 0)
        ) { "请选择指定用户或全体账户" }
        schema()
        if (!request.all) {
            require(repository.accountTargetExists(request.userId!!)) {
                "用户不存在，请填写账户 UID（QQ 号）"
            }
        }
        return repository.grant(actor, request)
    }

    suspend fun use(principal: LLMPrincipal, requestId: String): ResetUseResult {
        validateId(requestId)
        store.reconcileKnownUsage(principal.qqUid)
        store.cancelZombiePending(principal.qqUid)
        val redemption = repository.redeem(
            principal.qqUid,
            requestId,
            quota.period(Instant.now()).toString(),
        )
        return when (redemption.status) {
            ResetCardRedemptionStatus.ALREADY_REDEEMED,
            ResetCardRedemptionStatus.REDEEMED -> ResetUseResult(requestId, redemption.restoredUnits)
            ResetCardRedemptionStatus.ACTIVE_REQUESTS ->
                throw SettingsConflict("仍有请求执行中或等待结算，请稍后使用 reset 卡")
            ResetCardRedemptionStatus.NO_CARDS ->
                throw SettingsConflict("没有可用的 reset 卡")
            ResetCardRedemptionStatus.NO_USAGE ->
                throw SettingsConflict("本周用量为零，无需消耗 reset 卡")
        }
    }
}
