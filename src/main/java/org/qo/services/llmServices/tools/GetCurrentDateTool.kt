package org.qo.services.llmServices.tools

import com.google.gson.JsonObject
import org.qo.services.llmServices.LLMToolContext
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.DateTimeException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Component
class GetCurrentDateTool : Tools {
	override val id = "get_current_date"
	override val definition = ToolSupport.functionTool(
		name = id,
		description = "获取当前日期和时间。用户询问今天日期、当前时间、星期几或指定时区的当前时间时使用。默认时区为 Asia/Shanghai，不要用联网搜索查询本机当前日期。",
		properties = linkedMapOf(
			"timezone" to ToolSupport.property(
				type = "string",
				description = "可选的 IANA 时区，例如 Asia/Shanghai、UTC 或 America/Los_Angeles；省略时使用 Asia/Shanghai。",
			),
		),
	)

	override suspend fun execute(args: JsonObject, context: LLMToolContext): String =
		executeAt(args, Clock.systemUTC())

	internal fun executeAt(args: JsonObject, clock: Clock): String {
		val requestedZone = args.get("timezone")
			?.takeIf { !it.isJsonNull }
			?.asString
			?.trim()
			.orEmpty()
		val zoneName = requestedZone.ifBlank { DEFAULT_ZONE_ID }
		val zone = try {
			ZoneId.of(zoneName)
		} catch (_: DateTimeException) {
			return ToolSupport.errorResult(
				"invalid_timezone",
				"不支持的时区：$zoneName。请使用 IANA 时区，例如 Asia/Shanghai 或 UTC。",
			)
		}

		val now = ZonedDateTime.now(clock.withZone(zone))
		return ToolSupport.gson.toJson(JsonObject().apply {
			addProperty("tool", id)
			addProperty("timezone", zone.id)
			addProperty("datetime", DATE_TIME_FORMATTER.format(now))
			addProperty("date", DATE_FORMATTER.format(now))
			addProperty("time", TIME_FORMATTER.format(now))
			addProperty("day_of_week", now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
			addProperty("day_of_week_zh", now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.SIMPLIFIED_CHINESE))
			addProperty("offset", now.offset.id)
			addProperty("unix_timestamp", now.toEpochSecond())
			addProperty("unix_timestamp_ms", now.toInstant().toEpochMilli())
		})
	}

	private companion object {
		const val DEFAULT_ZONE_ID = "Asia/Shanghai"
		val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
		val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
		val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
	}
}
