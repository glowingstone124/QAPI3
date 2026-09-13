package org.qo.services.llmServices

import com.google.gson.JsonParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.qo.TestApiApplication
import org.qo.datas.ReactiveDatabase
import org.qo.datas.R2dbcDatabaseConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.math.BigDecimal
import java.time.Instant
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.*

@SpringBootTest(classes=[TestApiApplication::class,ReactiveDatabase::class,R2dbcDatabaseConfiguration::class])
class AiBillingIntegrationTest {
    @Autowired lateinit var db: ReactiveDatabase
    lateinit var store: SqlLLMQuotaStore
    lateinit var service: LLMDailyQuotaService
    private val principal=LLMPrincipal(123456,"Tester",LLMSource.WEB,"test")
    private val now=Instant.now()
    @BeforeEach fun setup() = runBlocking {
        store=SqlLLMQuotaStore(db); store.schema(); service=LLMDailyQuotaService(store,90,30,"Asia/Shanghai")
        for(table in listOf("ai_system_usage","ai_usage","ai_credit_ledger","ai_payment_order","ai_purchase_intent","ai_quota_reservation","ai_weekly_usage","ai_quota_account","ai_free_budget")) db.execute("DELETE FROM $table")
    }
    private fun usage(units: Int)=AiQuotaUsage(10,20,0,5,BigDecimal("0.005")*BigDecimal(units),units)
    @Test fun `reserve and actual settle span weekly and paid pools exactly once`() = runBlocking {
        db.execute("INSERT INTO ai_quota_account VALUES (?,800)",listOf(principal.qqUid))
        db.execute("INSERT INTO ai_weekly_usage VALUES (?,?,78)",listOf(principal.qqUid,service.period(now).toString()))
        val r=service.reserve(principal,"cross",now,30,estimatedCost=BigDecimal("0.15")).reservation!!
        assertEquals(782,service.snapshot(principal.qqUid).view.paidCredits)
        val view=service.settle(r,usage(20))
        assertEquals(90,view.used); assertEquals(792,view.paidCredits)
        assertEquals(view,service.settle(r,usage(20)))
        assertTrue(service.refund(r)); assertEquals(792,store.balance(principal.qqUid))
        assertEquals(1,db.one("SELECT COUNT(*) AS n FROM ai_usage") { (it.get("n") as Number).toInt() })
        val cost=db.one("SELECT actual_cost FROM ai_free_budget") { it.get("actual_cost") as BigDecimal }!!
        assertEquals(0,cost.compareTo(BigDecimal("0.06")))
    }
    @Test fun `old week refunds never increase current week usage and paid credits never reset`() = runBlocking {
        db.execute("INSERT INTO ai_quota_account VALUES (?,800)",listOf(principal.qqUid))
        val sunday=Instant.parse("2026-09-06T15:59:59Z")
        val r=service.reserve(principal,"sunday",sunday,100,estimatedCost=BigDecimal("0.5")).reservation!!
        assertEquals(790,store.balance(principal.qqUid))
        val monday=Instant.parse("2026-09-06T16:00:00Z")
        assertEquals(0,service.snapshot(principal.qqUid,true,monday).view.used)
        service.refund(r); service.refund(r)
        assertEquals(800,store.balance(principal.qqUid))
        assertEquals(monday.epochSecond,r.view.resetAtEpochSeconds)
        assertEquals(0,service.snapshot(principal.qqUid,true,monday).view.used)
    }
    @Test fun `same qq identity upgrade retains weekly usage and duplicate ids across sources are rejected`() = runBlocking {
        val qq=principal.copy(source=LLMSource.QQ,hasAccount=false)
        val r=service.reserve(qq,"shared",now,10,estimatedCost=BigDecimal("0.05")).reservation!!
        service.settle(r,usage(10))
        assertEquals(20,service.snapshot(principal.qqUid,false).view.remaining)
        assertEquals(80,service.snapshot(principal.qqUid,true).view.remaining)
        assertEquals(LLMQuotaStatus.DUPLICATE,service.reserve(principal,"shared").status)
    }
    @Test fun `database admission enforces real concurrency and rpm`() = runBlocking {
        val results=(1..3).map { i -> async { service.reserve(principal,"concurrent-$i") } }.awaitAll()
        assertEquals(2,results.count { it.status==LLMQuotaStatus.ACCEPTED })
        assertEquals(1,results.count { it.status==LLMQuotaStatus.RATE_LIMITED })
        results.mapNotNull { it.reservation }.forEach { service.refund(it) }
        for(i in 4..7) service.reserve(principal,"rpm-$i").reservation?.let { service.refund(it) }
        assertEquals(LLMQuotaStatus.RATE_LIMITED,service.reserve(principal,"rpm-blocked").status)
    }
    @Test fun `paid calls remain available when free thinking subsidy is gated`() = runBlocking {
        val month=now.atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate().withDayOfMonth(1).toString()
        db.execute("INSERT INTO ai_free_budget VALUES (?,90,0)",listOf(month))
        db.execute("INSERT INTO ai_quota_account VALUES (?,800)",listOf(principal.qqUid))
        val r=service.reserve(principal,"paid",mode="thinking",estimatedCost=BigDecimal("0.005")).reservation!!
        val view=service.settle(r,usage(1)); assertEquals(0,view.used); assertEquals(799,view.paidCredits)
        assertEquals(0,db.one("SELECT actual_cost FROM ai_free_budget") { it.get("actual_cost") as BigDecimal }!!.compareTo(BigDecimal("90")))
    }
    @Test fun `verified payment is atomic and replay cannot grant credits twice`() = runBlocking {
        val config = AfdianConfig(packs = listOf(
            CreditPack(5,350,"sku5","https://ifdian.net/order"),
            CreditPack(10,800,"sku10","https://ifdian.net/order"),
            CreditPack(20,1800,"sku20","https://ifdian.net/order"),
        ))
        val payments=AfdianCreditsService(db,store,object:AfdianOrderQuery { override suspend fun query(orderNo:String)=error("not called") },config)
        val intent=payments.intent(principal.qqUid,10)
        val id=intent.get("purchase_intent").asString
        val order=JsonParser.parseString("""{"status":2,"product_type":1,"out_trade_no":"order-123","custom_order_id":"$id","total_amount":"10.00","sku_detail":[{"sku_id":"sku10","count":1}]}""").asJsonObject
        (1..5).map { async { payments.applyVerified(order) } }.awaitAll()
        assertEquals(800,store.balance(principal.qqUid))
        assertEquals(1,db.one("SELECT COUNT(*) AS n FROM ai_payment_order") { (it.get("n") as Number).toInt() })
        order.addProperty("total_amount","9.00")
        assertFailsWith<IllegalArgumentException> { payments.applyVerified(order) }
        assertEquals(800,store.balance(principal.qqUid))
    }

    @Test fun `connectivity notifications acknowledge receipt without querying or granting credits`() = runBlocking {
        var queried = false
        val payments = AfdianCreditsService(db, store, object : AfdianOrderQuery {
            override suspend fun query(orderNo: String): com.google.gson.JsonObject {
                queried = true
                error("Connectivity notification must not query orders")
            }
        }, AfdianConfig())
        val controller = AfdianWebhookController(payments)
        for (body in listOf("{}", """{"ec":200,"em":"ok"}""", """{"data":{"type":"test"}}""")) {
            assertEquals(200, JsonParser.parseString(controller.webhook(body)).asJsonObject.get("ec").asInt)
        }
        assertFalse(queried)
        assertEquals(0, db.one("SELECT COUNT(*) AS n FROM ai_credit_ledger") { (it.get("n") as Number).toInt() })
        assertEquals(0, db.one("SELECT COUNT(*) AS n FROM ai_payment_order") { (it.get("n") as Number).toInt() })
        assertEquals(0, db.one("SELECT COUNT(*) AS n FROM ai_quota_account") { (it.get("n") as Number).toInt() })
    }

    @Test fun `regular sponsorship test orders are acknowledged without querying or granting credits`() = runBlocking {
        val payments = AfdianCreditsService(db, store, object : AfdianOrderQuery {
            override suspend fun query(orderNo: String): com.google.gson.JsonObject = error("Sponsorship test must not query orders")
        }, AfdianConfig())
        val body = """{"ec":200,"em":"ok","data":{"type":"order","order":{
            "out_trade_no":"202106232138371083454010626","user_id":"adf397fe8374811eaacee52540025c377",
            "plan_id":"a45353328af911eb973052540025c377","month":1,"total_amount":"5.00","show_amount":"5.00",
            "status":2,"remark":"","redeem_id":"","product_type":0,"discount":"0.00","sku_detail":[],
            "address_person":"","address_phone":"","address_address":""}}}"""
        assertEquals(200, JsonParser.parseString(AfdianWebhookController(payments).webhook(body)).asJsonObject.get("ec").asInt)
        assertEquals(0, db.one("SELECT COUNT(*) AS n FROM ai_credit_ledger") { (it.get("n") as Number).toInt() })
        assertEquals(0, db.one("SELECT COUNT(*) AS n FROM ai_payment_order") { (it.get("n") as Number).toInt() })
        assertEquals(0, db.one("SELECT COUNT(*) AS n FROM ai_quota_account") { (it.get("n") as Number).toInt() })
    }

    @Test fun `an order cannot masquerade as a connectivity notification to bypass verification`() = runBlocking {
        var queried = false
        val payments = AfdianCreditsService(db, store, object : AfdianOrderQuery {
            override suspend fun query(orderNo: String): com.google.gson.JsonObject {
                queried = true
                error("Invalid signatures must not query orders")
            }
        }, AfdianConfig())
        val controller = AfdianWebhookController(payments)
        for (body in listOf("""{"data":{"type":"order","order":{"out_trade_no":"fake"}}}""",
            """{"data":{"type":"order","order":{"out_trade_no":"fake","product_type":1}}}""",
            """{"data":{"type":"test","order":{}}}""", """{"data":{"order":"invalid"}}""", "[]", "null", "not-json")) {
            val error = assertFailsWith<org.springframework.web.server.ResponseStatusException> { controller.webhook(body) }
            assertEquals(400, error.statusCode.value())
        }
        assertFalse(queried)
        assertEquals(0, db.one("SELECT COUNT(*) AS n FROM ai_credit_ledger") { (it.get("n") as Number).toInt() })
    }
    @Test fun `cost counts cache and reasoning output once and unit conversion rounds up`() {
        val price=LLMModelPricing(BigDecimal("1"),BigDecimal("2"),BigDecimal("0.1"))
        assertEquals(BigDecimal("0.00031"),price.cost(100,150,100))
        for((cost,units) in listOf("0.004" to 1,"0.009" to 2,"0.025" to 5,"0.10" to 20)) assertEquals(units,LLMDailyQuotaService.units(BigDecimal(cost)))
    }

    @Test fun `failed calls refund both pools but record real cost precisely once`() = runBlocking {
        db.execute("INSERT INTO ai_quota_account VALUES (?,800)", listOf(principal.qqUid))
        db.execute("INSERT INTO ai_weekly_usage VALUES (?,?,89)", listOf(principal.qqUid, service.period(now).toString()))
        val r = service.reserve(principal, "failed", now, 3, estimatedCost=BigDecimal("0.015")).reservation!!
        val failed = AiQuotaUsage(10, 20, 0, null, BigDecimal("0.004"), 0)
        service.refundUsage(r, failed)
        service.refundUsage(r, failed)
        assertEquals(89, service.snapshot(principal.qqUid).view.used)
        assertEquals(800, store.balance(principal.qqUid))
        val cost = db.one("SELECT actual_cost FROM ai_free_budget") { it.get("actual_cost") as BigDecimal }!!
        assertEquals(0, cost.compareTo(BigDecimal("0.001333333333")))
        assertEquals(0, db.one("SELECT charged_units FROM ai_usage") { (it.get("charged_units") as Number).toInt() })
    }

    @Test fun `known pending usage settles after credits arrive and cannot charge twice`() = runBlocking {
        val r = service.reserve(principal, "pending", now, 5, estimatedCost=BigDecimal("0.025")).reservation!!
        assertFailsWith<IllegalArgumentException> { service.settle(r, usage(100)) }
        service.retain(r, usage(100))
        service.refund(r)
        assertEquals(5, service.snapshot(principal.qqUid).view.used)
        db.execute("UPDATE ai_quota_account SET paid_credits=10 WHERE user_id=?", listOf(principal.qqUid))
        assertEquals(1, store.reconcileKnownUsage(principal.qqUid))
        assertEquals(0, store.reconcileKnownUsage(principal.qqUid))
        assertEquals(90, service.snapshot(principal.qqUid).view.used)
        assertEquals(0, store.balance(principal.qqUid))
    }
    @Test fun `api signing matches official example and webhook RSA rejects tampering`() {
        assertEquals("a4acc28b81598b7e5d84ebdc3e91710c",AfdianSignature.api("123","{\"a\":333}",1624339905,"abc"))
        val pair=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val order=JsonParser.parseString("""{"out_trade_no":"order","user_id":"buyer","plan_id":"plan","total_amount":"5.00"}""").asJsonObject
        val signature=Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update("orderbuyerplan5.00".toByteArray()) }.sign()
        val pem="-----BEGIN PUBLIC KEY-----\n"+Base64.getEncoder().encodeToString(pair.public.encoded)+"\n-----END PUBLIC KEY-----"
        assertTrue(AfdianSignature.verify(order,Base64.getEncoder().encodeToString(signature),pem))
        order.addProperty("total_amount","20.00")
        assertFalse(AfdianSignature.verify(order,Base64.getEncoder().encodeToString(signature),pem))
        // Check the supplied production key can be parsed, too.
        val encoded=AfdianSignature.PUBLIC_KEY.substringAfter("-----BEGIN PUBLIC KEY-----").substringBefore("-----END PUBLIC KEY-----").replace(Regex("\\s"),"")
        java.security.KeyFactory.getInstance("RSA").generatePublic(java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(encoded)))
    }
}
