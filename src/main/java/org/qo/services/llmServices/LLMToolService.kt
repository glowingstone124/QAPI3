package org.qo.services.llmServices

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.qo.services.gameStatusService.Status
import org.qo.services.metroServices.MetroServiceImpl
import org.springframework.stereotype.Service

@Service
class LLMToolService(
	private val status: Status,
	private val metroService: MetroServiceImpl,
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
		      "description": "查询 QO 地铁线路、站点或区间信息。用户询问地铁、线路、站点、坐标、方向时使用。",
		      "parameters": {
		        "type": "object",
		        "properties": {
		          "query": {
		            "type": "string",
		            "description": "站点名、区间名、线路名或关键词。"
		          },
		          "line_id": {
		            "type": "integer",
		            "description": "线路编号 lid。"
		          },
		          "station_only": {
		            "type": "boolean",
		            "description": "是否只返回站点。"
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
		val query = args.get("query")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
		val lineId = args.get("line_id")?.takeIf { !it.isJsonNull }?.asInt
		val stationOnly = args.get("station_only")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
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
