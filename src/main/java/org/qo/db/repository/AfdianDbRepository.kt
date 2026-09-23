package org.qo.db.repository

import com.google.gson.JsonObject
import org.qo.datas.ReactiveDatabase
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant

@Repository
class AfdianDbRepository(
	private val database: ReactiveDatabase,
) {
	suspend fun insertPurchaseIntent(
		id: String,
		userId: Long,
		skuId: String,
		amount: BigDecimal,
		credits: Int,
		createdAt: Long = Instant.now().epochSecond,
	) {
		database.execute(
			"INSERT INTO ai_purchase_intent (id,user_id,sku_id,amount,credits,status,created_at) VALUES (?,?,?,?,?,'pending',?)",
			listOf(id, userId, skuId, amount, credits, createdAt),
		)
	}

	suspend fun insertPendingOrder(orderNo: String, nextAttemptAt: Long, now: Long = Instant.now().epochSecond) {
		database.execute(
			"INSERT INTO ai_afdian_pending_order (out_trade_no,status,attempts,next_attempt_at,last_error,created_at) VALUES (?,'pending',0,?,'order_not_found',?) ON DUPLICATE KEY UPDATE out_trade_no=out_trade_no",
			listOf(orderNo, nextAttemptAt, now),
		)
	}

	suspend fun getPendingOrders(now: Long, limit: Int = 10): List<Pair<String, Int>> {
		return database.all(
			"SELECT out_trade_no,attempts FROM ai_afdian_pending_order WHERE status='pending' AND next_attempt_at<=? ORDER BY next_attempt_at LIMIT ?",
			listOf(now, limit),
		) { row ->
			row.get("out_trade_no", String::class.java)!! to (row.get("attempts") as Number).toInt()
		}
	}

	suspend fun deletePendingOrder(orderNo: String) {
		database.execute("DELETE FROM ai_afdian_pending_order WHERE out_trade_no=?", listOf(orderNo))
	}

	suspend fun updatePendingOrder(nextAttemptAt: Long, error: String, status: String, orderNo: String) {
		database.execute(
			"UPDATE ai_afdian_pending_order SET attempts=attempts+1,next_attempt_at=?,last_error=?,status=? WHERE out_trade_no=? AND status='pending'",
			listOf(nextAttemptAt, error, status, orderNo),
		)
	}

	suspend fun applyVerifiedPurchase(
		orderNo: String,
		customOrderId: String,
		amount: BigDecimal,
		sku: String,
		now: Long = Instant.now().epochSecond,
	) {
		val uid = database.one(
			"SELECT user_id FROM ai_purchase_intent WHERE id=?",
			listOf(customOrderId),
		) { (it.get("user_id") as Number).toLong() }
			?: return

		database.inTransaction {
			database.execute(
				"INSERT INTO ai_quota_account (user_id,paid_credits) VALUES (?,0) ON DUPLICATE KEY UPDATE user_id=user_id",
				listOf(uid),
			)
			database.one("SELECT user_id FROM ai_quota_account WHERE user_id=? FOR UPDATE", listOf(uid)) { true }
			val intent = database.one("SELECT * FROM ai_purchase_intent WHERE id=? FOR UPDATE", listOf(customOrderId)) {
				Triple(
					it.get("sku_id", String::class.java)!!,
					it.get("amount") as BigDecimal,
					(it.get("credits") as Number).toInt(),
				)
			}!!
			require(intent.first == sku && intent.second.compareTo(amount) == 0)
			val existing = database.one(
				"SELECT intent_id FROM ai_payment_order WHERE provider='afdian' AND out_trade_no=?",
				listOf(orderNo),
			) { it.get("intent_id", String::class.java)!! }
			if (existing != null) {
				require(existing == customOrderId)
				return@inTransaction
			}
			require(database.one("SELECT status FROM ai_purchase_intent WHERE id=?", listOf(customOrderId)) {
				it.get("status", String::class.java)
			} == "pending")

			database.execute(
				"INSERT INTO ai_payment_order (provider,out_trade_no,intent_id,user_id,amount,credits,created_at) VALUES ('afdian',?,?,?,?,?,?)",
				listOf(orderNo, customOrderId, uid, amount, intent.third, now),
			)
			database.execute(
				"UPDATE ai_quota_account SET paid_credits=paid_credits+? WHERE user_id=?",
				listOf(intent.third, uid),
			)
			database.execute(
				"INSERT INTO ai_credit_ledger (user_id,reference_id,delta,kind,created_at) VALUES (?,?,?,'purchase',?)",
				listOf(uid, orderNo, intent.third, now),
			)
			database.execute("UPDATE ai_purchase_intent SET status='paid' WHERE id=?", listOf(customOrderId))
		}
	}
}
