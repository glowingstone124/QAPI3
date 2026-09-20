package org.qo.services.llmServices

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

data class LLMPricingSchedule(
	val zone: ZoneId, val weekdays: Set<Int>,
	val windows: List<Pair<LocalTime, LocalTime>>, val multiplier: BigDecimal
) {
	init {
		require(weekdays.isNotEmpty() && weekdays.all { it in 1..7 })
		require(windows.isNotEmpty() && windows.all { it.first < it.second })
		require(multiplier.signum() > 0)
	}

	fun applies(at: Instant): Boolean {
		val local = at.atZone(zone)
		return local.dayOfWeek.value in weekdays && windows.any { local.toLocalTime() >= it.first && local.toLocalTime() < it.second }
	}

	companion object {
		fun fromJson(obj: JsonObject) = LLMPricingSchedule(
			ZoneId.of(obj.get("zone").asString),
			obj.getAsJsonArray("weekdays").map { it.asInt }.toSet(),
			obj.getAsJsonArray("windows")
				.map { LocalTime.parse(it.asJsonArray[0].asString) to LocalTime.parse(it.asJsonArray[1].asString) },
			obj.get("multiplier").asBigDecimal
		)
	}
}

/** All prices are CNY, including reasoning in billable output tokens; cache is counted once. */
data class LLMModelPricing(
	val inputCnyPerMillion: BigDecimal, val outputCnyPerMillion: BigDecimal,
	val cachedInputCnyPerMillion: BigDecimal, val callCostCny: BigDecimal = BigDecimal.ZERO,
	val schedule: LLMPricingSchedule? = null
) {
	init {
		require(
			listOf(
				inputCnyPerMillion,
				outputCnyPerMillion,
				cachedInputCnyPerMillion,
				callCostCny
			).all { it.signum() >= 0 })
	}

	fun at(at: Instant): LLMModelPricing {
		val factor = if (schedule?.applies(at) == true) schedule.multiplier else BigDecimal.ONE
		return copy(
			inputCnyPerMillion = inputCnyPerMillion * factor, outputCnyPerMillion = outputCnyPerMillion * factor,
			cachedInputCnyPerMillion = cachedInputCnyPerMillion * factor, schedule = null
		)
	}

	fun cost(input: Long, output: Long, cached: Long, calls: Int = 1): BigDecimal {
		require(input >= 0 && output >= 0 && cached in 0..input && calls > 0)
		return (inputCnyPerMillion * BigDecimal(input - cached) + cachedInputCnyPerMillion * BigDecimal(cached) +
				outputCnyPerMillion * BigDecimal(output)).divide(BigDecimal(1_000_000)) + callCostCny * BigDecimal(calls)
	}

	companion object {
		fun fromJson(obj: JsonObject) = LLMModelPricing(
			obj.get("inputCnyPerMillion").asBigDecimal,
			obj.get("outputCnyPerMillion").asBigDecimal,
			obj.get("cachedInputCnyPerMillion")?.asBigDecimal ?: obj.get("inputCnyPerMillion").asBigDecimal,
			obj.get("callCostCny")?.asBigDecimal ?: BigDecimal.ZERO,
			obj.getAsJsonObject("schedule")?.let(LLMPricingSchedule::fromJson)
		)
	}
}

internal class QuotaSettlementException(cause: Exception) : RuntimeException("Usage settlement pending", cause)

internal suspend fun LLMServices.settleUsage(
	reservation: LLMQuotaReservation, usage: LLMServices.Usage?,
	request: LLMServices.NormalizedRequest, provider: LLMProvider, conversationId: String?
): LLMQuotaView {
	var charged: AiQuotaUsage? = null
	try {
		require(usage?.promptTokens != null && usage.completionTokens != null) { "Upstream omitted real usage" }
		require(usage.complete) { "Upstream omitted usage in a tool round" }
		val input = usage.promptTokens.toLong()
		val output = usage.completionTokens.toLong()
		val cache = (usage.cacheHitTokens ?: 0).toLong()
		val cost =
			requireNotNull(request.pricing ?: provider.modelConfig(request.preset).pricing?.at(Instant.now())).cost(
				input,
				output,
				cache,
				usage.apiCalls
			)
		charged = AiQuotaUsage(
			input,
			output,
			cache,
			usage.reasoningTokens,
			cost,
			LLMDailyQuotaService.units(cost),
			conversationId
		)
		return dailyQuotaService.settle(reservation, charged)
	} catch (error: Exception) {
		kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
			dailyQuotaService.retain(
				reservation,
				charged
			)
		}
		if (error is kotlinx.coroutines.CancellationException) throw error
		throw QuotaSettlementException(error)
	}
}

internal fun LLMServices.attachQuota(body: String, quota: LLMQuotaView): String =
	JsonParser.parseString(body).asJsonObject.apply {
		add("quota", JsonParser.parseString(quotaJson(quota)))
	}.toString()

internal fun LLMServices.Usage.plus(other: LLMServices.Usage) = LLMServices.Usage(
	(promptTokens ?: 0) + (other.promptTokens ?: 0),
	(completionTokens ?: 0) + (other.completionTokens ?: 0),
	(totalTokens ?: 0) + (other.totalTokens ?: 0),
	(cacheHitTokens ?: 0) + (other.cacheHitTokens ?: 0),
	(cacheMissTokens ?: 0) + (other.cacheMissTokens ?: 0),
	(reasoningTokens ?: 0) + (other.reasoningTokens ?: 0),
	apiCalls + other.apiCalls,
	complete && other.complete && promptTokens != null && completionTokens != null && other.promptTokens != null && other.completionTokens != null,
)

internal fun accumulateUsage(total: LLMServices.Usage?, next: LLMServices.Usage?): LLMServices.Usage {
	val round = next ?: LLMServices.Usage(complete = false)
	return total?.plus(round) ?: round
}

internal fun LLMServices.Usage.json(): JsonObject = JsonObject().apply {
	addProperty("prompt_tokens", promptTokens); addProperty("completion_tokens", completionTokens)
	addProperty("total_tokens", totalTokens); addProperty("prompt_cache_hit_tokens", cacheHitTokens)
	addProperty("qapi_api_calls", apiCalls)
	addProperty("qapi_usage_complete", complete)
	add("completion_tokens_details", JsonObject().apply { addProperty("reasoning_tokens", reasoningTokens) })
}

internal fun withUsage(body: String, usage: LLMServices.Usage?): String = if (usage == null) body else runCatching {
	JsonParser.parseString(body).asJsonObject.apply { add("usage", usage.json()) }.toString()
}.getOrDefault(body)

internal suspend fun LLMServices.refundUsage(
	reservation: LLMQuotaReservation, usage: LLMServices.Usage?,
	request: LLMServices.NormalizedRequest, provider: LLMProvider, conversationId: String?
) {
	val recorded = if (usage?.promptTokens != null && usage.completionTokens != null) {
		val cost =
			requireNotNull(request.pricing ?: provider.modelConfig(request.preset).pricing?.at(Instant.now())).cost(
				usage.promptTokens.toLong(),
				usage.completionTokens.toLong(),
				(usage.cacheHitTokens ?: 0).toLong(),
				usage.apiCalls
			)
		AiQuotaUsage(
			usage.promptTokens.toLong(),
			usage.completionTokens.toLong(),
			(usage.cacheHitTokens ?: 0).toLong(),
			usage.reasoningTokens,
			cost,
			0,
			conversationId
		)
	} else null
	dailyQuotaService.refundUsage(reservation, recorded)
}

internal fun publicModel(body: String, mode: String): String = runCatching {
	JsonParser.parseString(body).asJsonObject.apply { if (has("model")) addProperty("model", mode) }.toString()
}.getOrDefault(body)
