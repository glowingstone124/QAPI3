package org.qo.services.llmServices

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.qo.services.gameStatusService.Status
import org.qo.services.metroServices.MetroServiceImpl
import org.qo.services.transportationServices.Dimension
import org.qo.services.transportationServices.LineType
import org.qo.services.transportationServices.RouteConstraints
import org.qo.services.transportationServices.Station
import org.qo.services.transportationServices.TransportationServiceImpl
import org.springframework.stereotype.Service
import java.util.Locale

@Service
class LLMToolService(
	private val status: Status,
	private val metroService: MetroServiceImpl,
	private val transportationService: TransportationServiceImpl,
	private val ragService: RAGService,
) {
	private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
	private val jsonParser = JsonParser()
	private val maxMetroResults = readInt("LLM_TOOL_METRO_MAX_RESULTS", 12).coerceIn(1, 50)

	fun enabled(): Boolean = readBoolean("LLM_TOOLS_ENABLED", true)

	fun definitions(): JsonArray = jsonParser.parse(
		"""
		[
		  {
		    "type": "function",
		    "function": {
		      "name": "get_server_status",
		      "description": "查询 QO Minecraft 服务器当前状态、在线人数、总注册人数和 MSPT。用户询问服务器人数、在线人数、服务器状态时使用。",
		      "parameters": {
		        "type": "object",
		        "properties": {
		          "server_id": {
		            "type": "integer",
		            "description": "服务器编号。默认 1；survival/生存为 1，creative/创造为 4。"
		          },
		          "server_name": {
		            "type": "string",
		            "description": "服务器名称，可选 survival、生存、creative、创造。"
		          }
		        }
		      }
		    }
		  },
		  {
		    "type": "function",
		    "function": {
		      "name": "query_metro_lines",
		      "description": "查询 QO 地铁线路、站点、区间或计算路线。用户询问地铁、线路、站点、坐标、方向、从 A 到 B 怎么走、以及继续追问上一条路线是否可以步行/避开某类交通时使用。多轮追问时应从聊天历史继承上一条路线的 from/to，并按用户新要求设置 exclude_dims 或 exclude_types。",
		      "parameters": {
		        "type": "object",
		        "properties": {
		          "from": {
		            "type": "string",
		            "description": "路线起点站名或站点 ID。用户问从 A 到 B 怎么坐时填写。"
		          },
		          "to": {
		            "type": "string",
		            "description": "路线终点站名或站点 ID。用户问从 A 到 B 怎么坐时填写。"
		          },
		          "query": {
		            "type": "string",
		            "description": "站点名、区间名、线路名或关键词。不问路线时使用。"
		          },
		          "line_id": {
		            "type": "integer",
		            "description": "线路编号 lid。"
		          },
		          "station_only": {
		            "type": "boolean",
		            "description": "是否只返回站点。"
		          },
		          "exclude_dims": {
		            "type": "array",
		            "description": "路线计算时排除的维度。可选 overworld、nether、the_end。用户说不要走下界/只走主世界时使用；只走主世界等价于排除 nether 和 the_end。",
		            "items": {"type": "string"}
		          },
		          "exclude_types": {
		            "type": "array",
		            "description": "路线计算时排除的交通类型。可选 metro、rapid、blueice、citymetro、nether、pearl、airplane、boat、walk。用户问能不能步行时，不要在服务端推断，只由模型显式设置需要排除或保留的交通类型。",
		            "items": {"type": "string"}
		          }
		        }
		      }
		    }
		  },
		  {
		    "type": "function",
		    "function": {
		      "name": "search_minecraft_knowledge",
		      "description": "检索 Minecraft、QO 服务器玩法、指令、规则和知识库资料。用户询问 Minecraft 知识或服务器资料时使用。",
		      "parameters": {
		        "type": "object",
		        "properties": {
		          "query": {
		            "type": "string",
		            "description": "需要检索的问题或关键词。"
		          }
		        },
		        "required": ["query"]
		      }
		    }
		  }
		]
		""".trimIndent()
	).asJsonArray

	fun execute(name: String, rawArguments: String?, requesterGroupId: Long?): String {
		val args = parseArguments(rawArguments)
		return runCatching {
			when (name) {
				"get_server_status" -> getServerStatus(args)
				"query_metro_lines" -> queryMetroLines(args)
				"search_minecraft_knowledge" -> searchMinecraftKnowledge(args, requesterGroupId)
				else -> errorResult("unknown_tool", "未知工具：$name")
			}
		}.getOrElse { errorResult("tool_error", it.message ?: "工具执行失败") }
	}

	private fun getServerStatus(args: JsonObject): String {
		val serverId = args.get("server_id")?.takeIf { !it.isJsonNull }?.asInt
			?: serverIdFromName(args.get("server_name")?.takeIf { !it.isJsonNull }?.asString)
			?: 1
		val data = status.download(serverId)
		return gson.toJson(JsonObject().apply {
			addProperty("tool", "get_server_status")
			addProperty("server_id", serverId)
			add("status", data.deepCopy())
		})
	}

	private fun serverIdFromName(name: String?): Int? {
		val normalized = name?.trim()?.lowercase().orEmpty()
		return when (normalized) {
			"survival", "生存", "生存服" -> 1
			"creative", "创造", "创造服" -> 4
			else -> null
		}
	}

	private fun queryMetroLines(args: JsonObject): String {
		val from = args.get("from")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
		val to = args.get("to")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
		val constraints = parseRouteConstraints(args)
		if (from.isNotBlank() && to.isNotBlank()) {
			return calculateTransportationRoute(from, to, constraints)
		}
		val query = args.get("query")?.takeIf { !it.isJsonNull }?.asString?.trim()
			?: args.get("line")?.takeIf { !it.isJsonNull }?.asString?.trim()
			?: args.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim()
			?: ""
		val lineId = args.get("line_id")?.takeIf { !it.isJsonNull }?.asInt
		val stationOnly = args.get("station_only")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
		val transportationResult = queryTransportation(query, lineId)
		if (transportationResult != null) {
			return transportationResult
		}
		val root = jsonParser.parse(metroService.getMetroJson()).asJsonObject
		val matches = JsonArray()
		root.entrySet().asSequence()
			.map { it.key to it.value.asJsonObject }
			.filter { (_, section) -> lineId == null || section.get("lid")?.asInt == lineId }
			.filter { (_, section) -> !stationOnly || section.get("station")?.asBoolean == true }
			.filter { (id, section) ->
				query.isBlank() ||
					id.contains(query, ignoreCase = true) ||
					(section.get("dummy")?.asString?.contains(query, ignoreCase = true) == true) ||
					(section.get("lid")?.asString == query)
			}
			.take(maxMetroResults)
			.forEach { (id, section) ->
				matches.add(JsonObject().apply {
					addProperty("id", id)
					addProperty("lid", section.get("lid")?.asInt)
					addProperty("station", section.get("station")?.asBoolean)
					addProperty("name", section.get("dummy")?.asString.orEmpty())
					section.get("signal")?.let { add("signal", it) }
				})
			}
		return gson.toJson(JsonObject().apply {
			addProperty("tool", "query_metro_lines")
			addProperty("query", query)
			lineId?.let { addProperty("line_id", it) }
			addProperty("station_only", stationOnly)
			addProperty("returned", matches.size())
			add("matches", matches)
		})
	}

	private fun calculateTransportationRoute(from: String, to: String, constraints: RouteConstraints): String {
		val fromCandidates = findStations(from)
		val toCandidates = findStations(to)
		if (fromCandidates.isEmpty() || toCandidates.isEmpty()) {
			return gson.toJson(JsonObject().apply {
				addProperty("tool", "query_metro_lines")
				addProperty("mode", "route")
				addProperty("found", false)
				addProperty("message", "起点或终点没有匹配到站点。")
				add("constraints", constraintsToJson(constraints))
				add("from_candidates", stationsToJson(fromCandidates))
				add("to_candidates", stationsToJson(toCandidates))
			})
		}

		val attempts = fromCandidates.take(3).flatMap { fromStation ->
			toCandidates.take(3).map { toStation -> fromStation to toStation }
		}
		for ((fromStation, toStation) in attempts) {
			val route = transportationService.calculateRoute(fromStation.ID, toStation.ID, constraints) ?: continue
			return gson.toJson(JsonObject().apply {
				addProperty("tool", "query_metro_lines")
				addProperty("mode", "route")
				addProperty("found", true)
				add("constraints", constraintsToJson(constraints))
				add("from", stationToJson(fromStation))
				add("to", stationToJson(toStation))
				add("route", gson.toJsonTree(route))
			})
		}

		return gson.toJson(JsonObject().apply {
			addProperty("tool", "query_metro_lines")
			addProperty("mode", "route")
			addProperty("found", false)
			addProperty("message", "站点存在，但没有计算到可用路线。")
			add("constraints", constraintsToJson(constraints))
			add("from_candidates", stationsToJson(fromCandidates))
			add("to_candidates", stationsToJson(toCandidates))
		})
	}

	private fun parseRouteConstraints(args: JsonObject): RouteConstraints {
		val explicitDims = parseStringArray(args, "exclude_dims") + parseStringArray(args, "banned_dims")
		val explicitTypes = parseStringArray(args, "exclude_types") + parseStringArray(args, "banned_types")
		val avoid = listOf(
			args.get("avoid")?.takeIf { !it.isJsonNull }?.asString,
			args.get("preference")?.takeIf { !it.isJsonNull }?.asString,
		).filterNotNull().joinToString(" ")

		val bannedDims = explicitDims.mapNotNull(::parseDimension).toMutableSet()
		val bannedTypes = explicitTypes.mapNotNull(::parseLineType).toMutableSet()
		val normalizedAvoid = avoid.lowercase(Locale.ROOT)
		if ("下界" in avoid || "nether" in normalizedAvoid) {
			bannedDims.add(Dimension.NETHER)
			bannedTypes.add(LineType.NETHER)
		}
		if ("末地" in avoid || "the_end" in normalizedAvoid || "end" in normalizedAvoid) {
			bannedDims.add(Dimension.THE_END)
		}
		if ("步行" in avoid || "walk" in normalizedAvoid) {
			bannedTypes.add(LineType.WALK)
		}
		if ("蓝冰" in avoid || "blueice" in normalizedAvoid || "blue_ice" in normalizedAvoid) {
			bannedTypes.add(LineType.BLUEICE)
		}
		if ("只走主世界" in avoid || "仅主世界" in avoid || "主世界" in avoid && ("只" in avoid || "仅" in avoid || "only" in normalizedAvoid)) {
			bannedDims.add(Dimension.NETHER)
			bannedDims.add(Dimension.THE_END)
		}
		return RouteConstraints(bannedDimensions = bannedDims, bannedLineTypes = bannedTypes)
	}

	private fun parseStringArray(args: JsonObject, field: String): List<String> {
		val value = args.get(field)?.takeIf { !it.isJsonNull } ?: return emptyList()
		return when {
			value.isJsonArray -> value.asJsonArray.mapNotNull { it.takeIf { item -> !item.isJsonNull }?.asString }
			value.isJsonPrimitive -> value.asString.split(",").map { it.trim() }.filter { it.isNotBlank() }
			else -> emptyList()
		}
	}

	private fun parseDimension(value: String): Dimension? {
		return when (normalizeEnumToken(value)) {
			"OVERWORLD", "主世界" -> Dimension.OVERWORLD
			"NETHER", "下界", "地狱" -> Dimension.NETHER
			"THE_END", "THEEND", "END", "末地" -> Dimension.THE_END
			else -> null
		}
	}

	private fun parseLineType(value: String): LineType? {
		val normalized = normalizeEnumToken(value)
		return LineType.entries.firstOrNull {
			it.name == normalized || it.name.replace("_", "") == normalized.replace("_", "")
		} ?: when (normalized) {
			"地铁" -> LineType.METRO
			"快线", "快速" -> LineType.RAPID
			"蓝冰", "蓝冰道" -> LineType.BLUEICE
			"市域", "城市地铁" -> LineType.CITYMETRO
			"下界", "地狱" -> LineType.NETHER
			"珍珠炮", "珍珠" -> LineType.PEARL
			"飞机", "机场" -> LineType.AIRPLANE
			"船", "船道" -> LineType.BOAT
			"步行", "走路" -> LineType.WALK
			else -> null
		}
	}

	private fun constraintsToJson(constraints: RouteConstraints): JsonObject = JsonObject().apply {
		add("exclude_dims", JsonArray().apply {
			constraints.bannedDimensions.map { it.name }.forEach(::add)
		})
		add("exclude_types", JsonArray().apply {
			constraints.bannedLineTypes.map { it.name }.forEach(::add)
		})
	}

	private fun normalizeEnumToken(value: String): String {
		return value
			.substringAfter(':')
			.trim()
			.replace("-", "_")
			.replace(" ", "_")
			.uppercase(Locale.ROOT)
	}

	private fun queryTransportation(query: String, lineId: Int?): String? = runCatching {
		val stations = if (query.isBlank()) {
			transportationService.listStations().take(maxMetroResults)
		} else {
			findStations(query).take(maxMetroResults)
		}
		val lines = when {
			lineId != null -> transportationService.getLineById(lineId)?.let { listOf(it) }.orEmpty()
			query.isNotBlank() -> transportationService.queryLinesByName(query).take(maxMetroResults)
			else -> transportationService.listLines().take(maxMetroResults)
		}
		if (stations.isEmpty() && lines.isEmpty()) {
			return@runCatching null
		}
		gson.toJson(JsonObject().apply {
			addProperty("tool", "query_metro_lines")
			addProperty("mode", "search")
			addProperty("query", query)
			lineId?.let { addProperty("line_id", it) }
			addProperty("station_returned", stations.size)
			addProperty("line_returned", lines.size)
			add("stations", stationsToJson(stations))
			add("lines", gson.toJsonTree(lines))
		})
	}.getOrNull()

	private fun findStations(query: String): List<Station> {
		val normalized = normalizeSearch(query)
		if (normalized.isBlank()) {
			return emptyList()
		}
		transportationService.getStationById(query)?.let { return listOf(it) }
		val directMatches = runCatching { transportationService.queryStationsByName(query) }.getOrDefault(emptyList())
		if (directMatches.isNotEmpty()) {
			return directMatches.sortedByDescending { stationScore(it, normalized) }
		}
		return runCatching {
			transportationService.listStations()
				.map { it to stationScore(it, normalized) }
				.filter { (_, score) -> score > 0 }
				.sortedByDescending { (_, score) -> score }
				.map { (station, _) -> station }
		}.getOrDefault(emptyList())
	}

	private fun stationScore(station: Station, normalizedQuery: String): Int {
		val name = normalizeSearch(station.NAME)
		val nameEn = normalizeSearch(station.NAME_EN)
		val id = normalizeSearch(station.ID)
		return maxOf(
			tokenScore(name, normalizedQuery),
			tokenScore(nameEn, normalizedQuery),
			tokenScore(id, normalizedQuery),
		)
	}

	private fun tokenScore(value: String, query: String): Int {
		if (value.isBlank() || query.isBlank()) {
			return 0
		}
		if (value == query) return 100
		if (value.contains(query)) return 80
		if (query.contains(value)) return 60
		val overlap = query.toSet().count { it in value.toSet() }
		return if (overlap >= minOf(2, query.length)) overlap * 10 else 0
	}

	private fun stationsToJson(stations: List<Station>): JsonArray = JsonArray().apply {
		stations.take(maxMetroResults).forEach { add(stationToJson(it)) }
	}

	private fun stationToJson(station: Station): JsonObject = gson.toJsonTree(station).asJsonObject.apply {
		addProperty("id", station.ID)
		addProperty("name", station.NAME)
		addProperty("name_en", station.NAME_EN)
	}

	private fun normalizeSearch(value: String): String =
		value.trim()
			.lowercase(Locale.ROOT)
			.replace("\\s+".toRegex(), "")

	private fun searchMinecraftKnowledge(args: JsonObject, groupId: Long?): String {
		val query = args.get("query")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
		if (query.isBlank()) {
			return errorResult("bad_arguments", "query 不能为空")
		}
		val context = ragService.buildContext(query, groupId)
		return gson.toJson(JsonObject().apply {
			addProperty("tool", "search_minecraft_knowledge")
			addProperty("query", query)
			addProperty("found", context != null)
			addProperty("content", context ?: "知识库没有检索到相关资料。")
		})
	}

	private fun parseArguments(rawArguments: String?): JsonObject {
		if (rawArguments.isNullOrBlank()) {
			return JsonObject()
		}
		return runCatching { jsonParser.parse(rawArguments).asJsonObject }.getOrDefault(JsonObject())
	}

	private fun errorResult(code: String, message: String): String =
		gson.toJson(JsonObject().apply {
			addProperty("error", code)
			addProperty("message", message)
		})

	private fun readInt(name: String, defaultValue: Int): Int =
		System.getenv(name)?.trim()?.toIntOrNull() ?: defaultValue

	private fun readBoolean(name: String, defaultValue: Boolean): Boolean =
		when (System.getenv(name)?.trim()?.lowercase()) {
			"1", "true", "yes", "on" -> true
			"0", "false", "no", "off" -> false
			else -> defaultValue
		}
}
