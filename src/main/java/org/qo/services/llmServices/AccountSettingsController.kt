package org.qo.services.llmServices

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import org.qo.utils.AuthTokens
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/qo/asking/v1", produces=["application/json"])
class AccountSettingsController(private val llm: LLMServices, private val settings: AccountSettingsService) {
    private val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
    private fun response(status: Int, value: Any) = ResponseEntity.status(status).header("Cache-Control","no-store").body(gson.toJson(value))
    private suspend fun authenticated(auth: String?, block: suspend (LLMPrincipal) -> Any): ResponseEntity<String> {
        val token = AuthTokens.resolve(null,auth) ?: return response(401,mapOf("error" to mapOf("message" to "请先登录")))
        val principal = llm.authenticateWeb(token) ?: return response(401,mapOf("error" to mapOf("message" to "登录状态已失效")))
        return try { response(200,block(principal)) }
        catch (e: CancellationException) { throw e }
        catch (e: SettingsConflict) { response(409,mapOf("error" to mapOf("message" to e.message))) }
        catch (e: IllegalArgumentException) { response(400,mapOf("error" to mapOf("message" to e.message))) }
    }
    @GetMapping("/settings")
    suspend fun read(@RequestHeader("Authorization",required=false) auth: String?) = authenticated(auth) { principal ->
        val state = settings.settings(principal)
        gson.toJsonTree(state).asJsonObject.apply {
            add("quota",gson.toJsonTree(mapOf("limit" to state.quota.limit,"used" to state.quota.used,
                "remaining" to state.quota.remaining,"reset_at" to state.quota.resetAtEpochSeconds,"paid_credits" to state.quota.paidCredits)))
        }
    }
    @GetMapping("/usage/history")
    suspend fun history(@RequestHeader("Authorization",required=false) auth: String?, @RequestParam(defaultValue="0") page: Int) =
        authenticated(auth) { settings.history(it.qqUid,page) }

    @PostMapping("/reset-cards/use")
    suspend fun use(@RequestHeader("Authorization",required=false) auth: String?, @RequestBody body: String) = authenticated(auth) {
        val obj = parse(body)
        settings.use(it,string(obj,"request_id"))
    }
    @PostMapping("/reset-cards/bot")
    suspend fun useForBot(
        @RequestHeader("token", required=false) token: String?,
        @RequestHeader("Authorization", required=false) authorization: String?,
        @RequestHeader("X-QQ-UID") qqUid: Long,
        @RequestHeader("X-QQ-Name", required=false) qqName: String?,
        @RequestBody body: String,
    ): ResponseEntity<String> {
        val requestToken = AuthTokens.resolve(token, authorization)
            ?: return response(401,mapOf("error" to mapOf("message" to "缺少或无效的令牌")))
        if (!llm.authenticateServerToken(requestToken))
            return response(401,mapOf("error" to mapOf("message" to "Bot token 验证失败")))
        val principal = llm.qqPrincipal(qqUid, qqName)
            ?: return response(403,mapOf("error" to mapOf("message" to "该用户暂时不能使用此功能")))
        return try { response(200, settings.use(principal, string(parse(body), "request_id"))) }
        catch (e: SettingsConflict) { response(409,mapOf("error" to mapOf("message" to e.message))) }
        catch (e: IllegalArgumentException) { response(400,mapOf("error" to mapOf("message" to e.message))) }
    }

    @PostMapping("/reset-cards/grant")
    suspend fun grant(@RequestHeader("Authorization",required=false) auth: String?, @RequestBody body: String): ResponseEntity<String> {
        val token = AuthTokens.resolve(null,auth) ?: return response(401,mapOf("error" to mapOf("message" to "请先登录")))
        val principal = llm.authenticateWeb(token) ?: return response(401,mapOf("error" to mapOf("message" to "登录状态已失效")))
        if (!settings.canGrant(principal.qqUid)) return response(403,mapOf("error" to mapOf("message" to "没有派发权限")))
        return authenticated(auth) {
            val obj = parse(body)
            settings.grant(it.qqUid,ResetGrantRequest(
                string(obj,"request_id"),
                integer(obj,"count")?.also { value -> require(value in 1..100) { "每次可派发 1 至 100 张" } }?.toInt() ?: 1,
                integer(obj,"user_id"),
                boolean(obj,"all")))
        }
    }
    private fun string(obj: JsonObject, key: String): String {
        val value = obj.get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$key 必须为字符串" }
        return value.asString
    }
    private fun integer(obj: JsonObject, key: String): Long? {
        val value = obj.get(key)?.takeUnless { it.isJsonNull } ?: return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$key 必须为整数" }
        return value.toString().toLongOrNull() ?: throw IllegalArgumentException("$key 必须为整数")
    }
    private fun boolean(obj: JsonObject, key: String): Boolean {
        val value = obj.get(key) ?: return false
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "$key 必须为布尔值" }
        return value.asBoolean
    }
    private fun parse(body: String) = try { JsonParser.parseString(body).asJsonObject }
        catch (_: Exception) { throw IllegalArgumentException("请求格式错误") }
}
