package org.qo.services.llmServices

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import jakarta.annotation.PreDestroy
import org.qo.datas.ReactiveDatabase
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.UUID

object AfdianSignature {
    fun api(token: String, params: String, ts: Long, userId: String): String = MessageDigest.getInstance("MD5")
        .digest("${token}params${params}ts${ts}user_id${userId}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun verify(order: JsonObject, signature: String, publicKey: String = PUBLIC_KEY): Boolean = runCatching {
        val bytes = Base64.getDecoder().decode(publicKey.replace("-----BEGIN PUBLIC KEY-----","")
            .replace("-----END PUBLIC KEY-----","").replace(Regex("\\s"),""))
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes))
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(key)
        verifier.update(listOf("out_trade_no","user_id","plan_id","total_amount").joinToString("") {
            requireNotNull(order.get(it)).asString
        }.toByteArray(Charsets.UTF_8))
        verifier.verify(Base64.getDecoder().decode(signature))
    }.getOrDefault(false)
    const val PUBLIC_KEY = """-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwwdaCg1Bt+UKZKs0R54y
lYnuANma49IpgoOwNmk3a0rhg/PQuhUJ0EOZSowIC44l0K3+fqGns3Ygi4AfmEfS
4EKbdk1ahSxu7Zkp2rHMt+R9GarQFQkwSS/5x1dYiHNVMiR8oIXDgjmvxuNes2Cr
8fw9dEF0xNBKdkKgG2qAawcN1nZrdyaKWtPVT9m2Hl0ddOO9thZmVLFOb9NVzgYf
jEgI+KWX6aY19Ka/ghv/L4t1IXmz9pctablN5S0CRWpJW3Cn0k6zSXgjVdKm4uN7
jRlgSRaf/Ind46vMCm3N2sgwxu/g3bnooW+db0iLo13zzuvyn727Q3UDQ0MmZcEW
MQIDAQAB
-----END PUBLIC KEY-----"""
}

data class CreditPack(val amount: Int, val credits: Int, val skuId: String, val checkoutUrl: String)
interface AfdianOrderQuery { suspend fun query(orderNo: String): JsonObject }
internal class AfdianOrderNotFoundException : IllegalStateException("Order not visible in the official query; retry required")

internal fun queriedAfdianOrder(root: JsonObject, orderNo: String): JsonObject {
    check(root.get("ec")?.asInt==200) { "Afdian rejected order query" }
    val matches = root.getAsJsonObject("data").getAsJsonArray("list").map { it.asJsonObject }
        .filter { it.get("out_trade_no")?.asString==orderNo }
    check(matches.size <= 1) { "Afdian returned duplicate order numbers" }
    return matches.singleOrNull() ?: throw AfdianOrderNotFoundException()
}

@Service
class HttpAfdianOrderQuery(
    private val config: AfdianConfig,
) : AfdianOrderQuery {
    private val client = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis=15_000; connectTimeoutMillis=5_000 } }
    @PreDestroy fun close() = client.close()
    override suspend fun query(orderNo: String): JsonObject {
        val userId = config.userId
        val token = config.apiToken
        val url = config.queryUrl
        check(userId.isNotBlank() && token.isNotBlank()) { "Afdian credentials are not configured" }
        require(URI(url).scheme=="https")
        val params = JsonObject().apply { addProperty("out_trade_no",orderNo) }.toString()
        val ts = Instant.now().epochSecond
        val body = JsonObject().apply {
            addProperty("user_id",userId); addProperty("params",params); addProperty("ts",ts)
            addProperty("sign",AfdianSignature.api(token,params,ts,userId))
        }
        val response = client.post(url) { contentType(ContentType.Application.Json); setBody(body.toString()) }
        check(response.status.value in 200..299) { "Afdian query failed" }
        val root = JsonParser.parseString(response.bodyAsText()).asJsonObject
        return queriedAfdianOrder(root,orderNo)
    }
}

@Service
class AfdianCreditsService(private val db: ReactiveDatabase, private val quota: SqlLLMQuotaStore,
    private val query: AfdianOrderQuery,
    config: AfdianConfig,
) {
    val packs = config.packs
    private val logger = LoggerFactory.getLogger(AfdianCreditsService::class.java)
    suspend fun intent(uid: Long, amount: Int): JsonObject {
        val pack = packs.singleOrNull { it.amount==amount } ?: throw IllegalArgumentException("Invalid credit pack")
        check(pack.skuId.isNotBlank() && pack.checkoutUrl.isNotBlank()) { "Credit pack is not configured" }
        require(URI(pack.checkoutUrl).scheme=="https" && URI(pack.checkoutUrl).host in setOf("ifdian.net","afdian.com","afdian.net"))
        require(!pack.checkoutUrl.contains("custom_order_id") && URI(pack.checkoutUrl).fragment==null)
        quota.schema()
        val id = UUID.randomUUID().toString()
        db.execute("INSERT INTO ai_purchase_intent (id,user_id,sku_id,amount,credits,status,created_at) VALUES (?,?,?,?,?,'pending',?)",
            listOf(id,uid,pack.skuId,BigDecimal(amount),pack.credits,Instant.now().epochSecond))
        val separator = if(pack.checkoutUrl.contains('?')) "&" else "?"
        return JsonObject().apply {
            addProperty("purchase_intent",id)
            addProperty("checkout_url",pack.checkoutUrl+separator+"custom_order_id="+URLEncoder.encode(id,Charsets.UTF_8))
            addProperty("credits",pack.credits)
        }
    }
    suspend fun reconcile(uid: Long): Int = quota.reconcileKnownUsage(uid)
    suspend fun webhook(body: JsonObject) {
        val dataElement = body.get("data")
        // Connectivity notifications carry no order and must never alter balances.
        if (dataElement == null || dataElement.isJsonNull) return
        require(dataElement.isJsonObject) { "Invalid webhook data" }
        val data = dataElement.asJsonObject
        val orderElement = data.get("order")
        if (orderElement == null || orderElement.isJsonNull) return
        require(orderElement.isJsonObject) { "Invalid webhook order" }
        require(data.get("type")?.asString=="order")
        val order = orderElement.asJsonObject
        // Regular sponsorships are not Credits products. Acknowledging them grants nothing.
        if (order.get("product_type")?.takeUnless { it.isJsonNull }?.asInt == 0) return
        require(AfdianSignature.verify(order,data.get("sign")?.asString ?: "")) { "Invalid webhook signature" }
        val no = order.get("out_trade_no").asString
        require(no.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        processVerifiedNotification(no)
    }
    /** Called only after webhook verification; an absent official order is never a payment. */
    internal suspend fun processVerifiedNotification(orderNo: String) {
        try {
            applyVerified(query.query(orderNo))
        } catch (_: AfdianOrderNotFoundException) {
            quota.schema()
            val now = Instant.now().epochSecond
            db.execute("INSERT INTO ai_afdian_pending_order (out_trade_no,status,attempts,next_attempt_at,last_error,created_at) VALUES (?,'pending',0,?,'order_not_found',?) ON DUPLICATE KEY UPDATE out_trade_no=out_trade_no",
                listOf(orderNo,now+30,now))
            logger.warn("Verified Afdian notification deferred: official order not found; durable retry queued")
        }
    }
    internal suspend fun retryPendingOrders(now: Long = Instant.now().epochSecond): Int {
        quota.schema()
        val pending = db.all("SELECT out_trade_no,attempts FROM ai_afdian_pending_order WHERE status='pending' AND next_attempt_at<=? ORDER BY next_attempt_at LIMIT 10",listOf(now)) {
            it.get("out_trade_no",String::class.java)!! to (it.get("attempts") as Number).toInt()
        }
        var completed = 0
        for ((orderNo,attempts) in pending) {
            try {
                applyVerified(query.query(orderNo))
                db.execute("DELETE FROM ai_afdian_pending_order WHERE out_trade_no=?",listOf(orderNo))
                completed++
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                val delay = minOf(3600L,30L shl attempts.coerceIn(0,7))
                val status = if (error is IllegalArgumentException) "review" else "pending"
                db.execute("UPDATE ai_afdian_pending_order SET attempts=attempts+1,next_attempt_at=?,last_error=?,status=? WHERE out_trade_no=? AND status='pending'",
                    listOf(now+delay,error.javaClass.simpleName.take(64),status,orderNo))
                if (status=="review") logger.warn("Pending Afdian order requires review: verified order failed purchase validation")
            }
        }
        return completed
    }
    /** Only a trusted server query can call this boundary. No webhook SKU or identity is used. */
    internal suspend fun applyVerified(order: JsonObject) {
        quota.schema()
        require(order.get("status")?.asInt==2)
        if(order.get("product_type")?.asInt!=1) return // Other donations do not purchase AI credits.
        val no = order.get("out_trade_no").asString
        val id = order.get("custom_order_id")?.asString?.takeIf { it.length<=64 } ?: return
        val amount = order.get("total_amount").asBigDecimal
        val skus = order.getAsJsonArray("sku_detail")
        require(skus.size()==1 && skus[0].asJsonObject.get("count")?.asInt==1)
        val sku = skus[0].asJsonObject.get("sku_id").asString
        val uid = db.one("SELECT user_id FROM ai_purchase_intent WHERE id=?",listOf(id)) { (it.get("user_id") as Number).toLong() }
            ?: return
        db.inTransaction {
            db.execute("INSERT INTO ai_quota_account (user_id,paid_credits) VALUES (?,0) ON DUPLICATE KEY UPDATE user_id=user_id",listOf(uid))
            db.one("SELECT user_id FROM ai_quota_account WHERE user_id=? FOR UPDATE",listOf(uid)) { true }
            val intent = db.one("SELECT * FROM ai_purchase_intent WHERE id=? FOR UPDATE",listOf(id)) {
                Triple(it.get("sku_id",String::class.java)!!,it.get("amount") as BigDecimal,(it.get("credits") as Number).toInt())
            }!!
            require(intent.first==sku && intent.second.compareTo(amount)==0)
            val existing = db.one("SELECT intent_id FROM ai_payment_order WHERE provider='afdian' AND out_trade_no=?",listOf(no)) { it.get("intent_id",String::class.java)!! }
            if(existing!=null) { require(existing==id); return@inTransaction }
            require(db.one("SELECT status FROM ai_purchase_intent WHERE id=?",listOf(id)) { it.get("status",String::class.java) }=="pending")
            val now=Instant.now().epochSecond
            db.execute("INSERT INTO ai_payment_order (provider,out_trade_no,intent_id,user_id,amount,credits,created_at) VALUES ('afdian',?,?,?,?,?,?)",listOf(no,id,uid,amount,intent.third,now))
            db.execute("UPDATE ai_quota_account SET paid_credits=paid_credits+? WHERE user_id=?",listOf(intent.third,uid))
            db.execute("INSERT INTO ai_credit_ledger (user_id,reference_id,delta,kind,created_at) VALUES (?,?,?,'purchase',?)",listOf(uid,no,intent.third,now))
            db.execute("UPDATE ai_purchase_intent SET status='paid' WHERE id=?",listOf(id))
        }
    }
}

@RestController
@RequestMapping("/qo/asking/v1/credits")
class AfdianCreditsController(private val llm: LLMServices, private val credits: AfdianCreditsService) {
    @PostMapping("/purchase",produces=["application/json"])
    suspend fun purchase(@RequestHeader("Authorization",required=false) authorization: String?, @RequestBody body: String): String {
        val token = authorization?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val principal = llm.authenticateWeb(token) ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        return try { credits.intent(principal.qqUid,JsonParser.parseString(body).asJsonObject.get("amount").asInt).toString() }
        catch (_: IllegalArgumentException) { throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid credit pack") }
        catch (_: IllegalStateException) { throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Payments not configured") }
    }
    @PostMapping("/reconcile",produces=["application/json"])
    suspend fun reconcile(@RequestHeader("Authorization",required=false) authorization: String?): String {
        val token=authorization?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ") ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val principal=llm.authenticateWeb(token) ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        return "{\"settled\":${credits.reconcile(principal.qqUid)}}"
    }
}

@RestController
class AfdianWebhookController(private val credits: AfdianCreditsService) {
    private val logger = LoggerFactory.getLogger(AfdianWebhookController::class.java)
    @PostMapping("/hooks/afdian",produces=["application/json"])
    suspend fun webhook(@RequestBody body: String): String {
        if(body.length>16384) throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE)
        try {
            val payload = runCatching { JsonParser.parseString(body) }
                .getOrElse { throw IllegalArgumentException("Invalid webhook JSON") }
            require(payload.isJsonObject) { "Invalid webhook payload" }
            credits.webhook(payload.asJsonObject)
        }
        catch (_: IllegalArgumentException) {
            logger.warn("Afdian webhook rejected: invalid payload or signature")
            throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Order verification failed")
        }
        catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            // Do not log payloads, credentials, or upstream responses.
            logger.warn("Afdian order verification unavailable ({})", error.javaClass.simpleName)
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Order verification unavailable")
        }
        return """{"ec":200,"em":""}"""
    }
}
