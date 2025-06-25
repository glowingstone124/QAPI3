package org.qo.services.loginService

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.reactive.awaitSingle
import org.qo.datas.Mapping
import org.qo.services.messageServices.Msg
import org.qo.utils.ReturnInterface
import org.qo.orm.SQL
import org.qo.redis.DatabaseType
import org.qo.redis.Redis
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.LocalDate

@Service
class AuthorityNeededServicesImpl(private val login: Login, private val ri: ReturnInterface, private val ft: FortuneTools, private val playerCardCustomizationImpl: PlayerCardCustomizationImpl) {
	val gson = Gson()
	val redis = Redis()
	data class WebChatWrapper(
		val message: String,
		val timestamp: Long,
	)

	suspend fun insertWebMessage(msg: String, token: String): Pair<Int, String> {
		val (username, errCode) = login.validate(token)
		doPrecheck(username, errCode)?.let {
			return Pair(1, getErrorMessage(1));
		}
		val resultJson = runCatching {
			gson.fromJson(msg, WebChatWrapper::class.java)
		}.onFailure {
			return Pair(20, getErrorMessage(20))
		}
		Msg.putWebchat(resultJson.getOrNull()?.message ?: "",username!!)
		return Pair(0, "ok")
	}

	suspend fun getAccountInfo(token: String): String {
		val (accountName, errorCode) = login.validate(token)
		val precheckResult = doPrecheck(accountName, errorCode)
		if (precheckResult != null) {
			return precheckResult
		}
		val userInfo = userORM.read(accountName!!)
		val returnObject = JsonObject().apply {
			addProperty("username", accountName)
			addProperty("uid", userInfo!!.uid)
			addProperty("playtime", userInfo.playtime)
		}
		val loginHistory = login.queryLoginHistory(username = accountName).convertToJsonArray()
		returnObject.add("logins", loginHistory)
		return returnObject.toString()
	}

	suspend fun calculateFortune(token: String): String {
		val (accountName, errorCode) = login.validate(token)
		val precheckResult = doPrecheck(accountName, errorCode)
		if (precheckResult != null) {
			return precheckResult
		}
		return gson.toJson(ft.calculateFortune(accountName!!))
	}

	suspend fun getIpWhitelists(token: String): String {
		val (accountName, errorCode) = login.validate(token)
		val precheckResult = doPrecheck(accountName, errorCode)
		if (precheckResult != null) {
			return precheckResult
		}
		val connection = SQL.getConnection()
		val ipCountResult = connection.createStatement("SELECT * FROM loginip WHERE username = ?")
			.bind(0, accountName!!)
			.execute()
			.awaitSingle().map { row, _ ->
				return@map row.get("ip", String::class.java) ?: "Unknown IP"
			}.collectList().awaitSingle().convertToJsonArray()

		return ipCountResult.toString()
	}

	/**
	 * precheck a username is presented in database.
	 * @return null if everything is ok, or reason
	 */
	suspend fun doPrecheck(accountName: String?, errorCode: Int): String? {
		if (accountName == null) {
			val errorMessage = getErrorMessage(errorCode)
			val returnObject = JsonObject().apply {
				addProperty("error", errorCode)
				addProperty("message", errorMessage)
			}
			return returnObject.toString()
		}

		val userInfo = userORM.read(accountName)
		if (userInfo == null) {
			val returnObject = JsonObject().apply {
				addProperty("error", 200)
				addProperty("message", "User not found.")
			}
			return returnObject.toString()
		}

		return null
	}

	fun getErrorMessage(errorCode: Int): String {
		return when (errorCode) {
			1 -> "Invalid token found."
			3 -> "Token expired."
			20 -> "Format error."
			else -> "Unknown error."
		}
	}

	suspend fun internalAuthorityCheck(token: String): Pair<Mapping.Users?, Boolean>{
		val (accountName, errorCode) = login.validate(token)
		val precheckResult = doPrecheck(accountName, errorCode)
		if (precheckResult != null) {
			return Pair(null, false)
		}
		return Pair(userORM.read(accountName!!),true)
	}
	fun getPlayerLogin(username: String): Pair<Boolean, String> {
		redis.exists("login_history_$username", DatabaseType.QO_TEMP_DATABASE.value).ignoreException()?.let {
			if (!it) {
				return Pair(false,"");
			} else {
				val result = redis.get("login_history_$username",DatabaseType.QO_TEMP_DATABASE.value).ignoreException().orEmpty()
				return Pair(true, result);
			}
		}
		return Pair(false,"");
	}
}

@Service
class FortuneTools {
	private val TIANGAN = "甲乙丙丁戊己庚辛壬癸"
	private val DIZHI = "子丑寅卯辰巳午未申酉戌亥"

	data class Fortune(
		val day: String,
		val love: Luck,
		val career: Luck,
		val wealth: Luck
	)

	data class Luck(
		val amount: Int,
		val comment: String
	)

	fun getBazi(): String {
		val now = LocalDate.now()
		val yearIndex = (now.year - 4) % 60
		val monthIndex = (now.monthValue + 1) % 12
		val dayIndex = (now.toEpochDay().toInt() + 15) % 60

		return "${TIANGAN[yearIndex % 10]}${DIZHI[yearIndex % 12]}年 " +
				"${TIANGAN[monthIndex % 10]}${DIZHI[monthIndex]}月 " +
				"${TIANGAN[dayIndex % 10]}${DIZHI[dayIndex % 12]}日"
	}

	fun hashValue(userId: String): Triple<Int, Int, Int> {
		val md5 = MessageDigest.getInstance("MD5").digest(userId.toByteArray()).joinToString("") { "%02x".format(it) }

		val base1 = (md5.substring(0, 8).toLong(16) % 100).toInt()
		val base2 = (md5.substring(8, 16).toLong(16) % 100).toInt()
		val base3 = (md5.substring(16, 24).toLong(16) % 100).toInt()

		return Triple(base1, base2, base3)
	}

	fun calculateFortune(userId: String): Fortune {
		val bazi = getBazi()
		val (base1, base2, base3) = hashValue(userId)

		val baziHash = bazi.sumOf { it.code } % 100

		val loveLuck = (base1 * baziHash) % 100
		val careerLuck = (base2 * baziHash) % 100
		val wealthLuck = (base3 * baziHash) % 100

		fun luckLevel(score: Int): Luck = when {
			score > 85 -> Luck(score, "运势爆棚，抓住机会！")
			score > 70 -> Luck(score, "整体顺利，可大胆行动。")
			score > 50 -> Luck(score, "稳定发挥，不宜冒进。")
			score > 30 -> Luck(score, "谨慎行事，小心波折。")
			else -> Luck(score, "运势低迷，宜避开重要决策。")
		}

		return Fortune(
			day = bazi,
			love = luckLevel(loveLuck),
			career = luckLevel(careerLuck),
			wealth = luckLevel(wealthLuck)
		)
	}

}
