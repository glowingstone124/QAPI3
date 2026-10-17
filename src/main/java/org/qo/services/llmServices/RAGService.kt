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
import io.ktor.http.isSuccess
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.math.min

@Service
class RAGService() {
	internal data class Config(
		val enabled: Boolean,
		val knowledgeDir: Path,
		val topK: Int,
		val maxContextChars: Int,
		val chunkSize: Int,
		val keywordMinScore: Double,
		val enableMnemos: Boolean = false,
		val mnemosEndpoint: String = "http://127.0.0.1:8089",
	)

	private var config = defaultConfig()
	private val initializationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val initialLoadStarted = AtomicBoolean(false)
	private val initialLoad = CompletableDeferred<Unit>()
	@Volatile
	private var chunks: List<RAGChunk> = emptyList()
	private var httpClient: HttpClient = defaultHttpClient()
	private var ownsHttpClient: Boolean = true

	internal constructor(config: Config) : this() {
		this.config = config
	}

	internal constructor(config: Config, httpClient: HttpClient) : this(config) {
		this.httpClient = httpClient
		this.ownsHttpClient = false
	}

	@PostConstruct
	fun init() {
		ensureInitialLoadScheduled()
	}

	@PreDestroy
	fun shutdown() {
		initializationScope.cancel()
		if (ownsHttpClient) {
			runCatching { httpClient.close() }
		}
	}

	suspend fun buildContext(question: String, groupId: Long? = null): String? {
		if (!config.enabled || question.isBlank()) {
			return null
		}
		awaitInitialLoad()
		val matches = if (config.enableMnemos) {
			searchMnemos(question, groupId)
		} else {
			search(question, groupId)
		}
		return formatContext(matches)
	}

	private fun formatContext(matches: List<RAGMatch>): String? {
		if (matches.isEmpty()) {
			return null
		}
		val sb = StringBuilder()
		sb.append("知识库资料如下。优先根据这些资料回答；资料未覆盖时，明确说明不确定，不要编造服务器规则。\n")
		var used = 0
		matches.forEachIndexed { index, match ->
			val content = match.chunk.content.trim()
			val remaining = config.maxContextChars - used
			if (remaining <= 0) return@forEachIndexed
			val clipped = content.take(remaining)
			used += clipped.length
			sb.append("\n[").append(index + 1).append("] ")
				.append("来源：").append(match.chunk.source)
				.append("；范围：").append(match.chunk.groupId ?: "common")
				.append("；标题：").append(match.chunk.title)
				.append("；检索：").append(match.strategy)
				.append("；相关度：").append("%.3f".format(Locale.ROOT, match.score))
				.append("\n")
				.append(clipped)
				.append("\n")
		}
		return sb.toString()
	}

	private suspend fun searchMnemos(question: String, groupId: Long?): List<RAGMatch> {
		val endpoint = "${config.mnemosEndpoint.trimEnd('/')}/v1/search"
		val requestJson = JsonObject().apply {
			addProperty("query", question)
			if (groupId != null) {
				addProperty("group_id", groupId)
			}
			addProperty("top_k", config.topK)
		}

		val response = runCatching {
			httpClient.post(endpoint) {
				contentType(ContentType.Application.Json)
				setBody(requestJson.toString())
			}
		}.getOrElse { error ->
			if (error is CancellationException) throw error
			println("[RAG] Mnemos search request failed: ${error.message}")
			return emptyList()
		}

		if (!response.status.isSuccess()) {
			println("[RAG] Mnemos returned HTTP ${response.status.value}")
			return emptyList()
		}

		val bodyText = runCatching { response.bodyAsText() }.getOrDefault("")
		val root = runCatching { JsonParser.parseString(bodyText).asJsonObject }.getOrNull()
			?: return emptyList()

		if (root.get("status")?.asString != "ok") {
			val errorMsg = root.getAsJsonObject("payload")?.get("message")?.asString ?: "Unknown error"
			println("[RAG] Mnemos returned error: $errorMsg")
			return emptyList()
		}

		val payload = root.getAsJsonObject("payload") ?: return emptyList()
		val matchesArray = payload.getAsJsonArray("matches") ?: return emptyList()
		val result = mutableListOf<RAGMatch>()
		for (element in matchesArray) {
			val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
			val id = item.get("id")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
			val source = item.get("source")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
			val title = item.get("title")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
			val content = item.get("content")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
			val matchGroupId = item.get("group_id")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asLong
			val score = item.get("score")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0

			result.add(
				RAGMatch(
					chunk = RAGChunk(
						id = id,
						source = source,
						title = title,
						content = content,
						groupId = matchGroupId,
					),
					score = score,
					strategy = "mnemos",
				)
			)
		}
		return result
	}

	suspend fun reload() {
		initialLoadStarted.set(true)
		if (config.enableMnemos) {
			println("RAG operating in Mnemos mode (endpoint: ${config.mnemosEndpoint})")
			runCatching {
				val endpoint = "${config.mnemosEndpoint.trimEnd('/')}/v1/reload"
				val response = httpClient.post(endpoint)
				println("RAG notified Mnemos reload: status=${response.status.value}")
			}.onFailure {
				if (it is CancellationException) throw it
				println("RAG notify Mnemos reload failed: ${it.message}")
			}
			chunks = emptyList()
			if (!initialLoad.isCompleted) {
				initialLoad.complete(Unit)
			}
			return
		}
		chunks = if (!config.enabled || !Files.isDirectory(config.knowledgeDir)) {
			emptyList()
		} else {
			withContext(Dispatchers.IO) {
				Files.walk(config.knowledgeDir).use { stream ->
					stream
						.filter { it.isRegularFile() }
						.filter { it.extension.lowercase(Locale.ROOT) in setOf("md", "txt") }
						.filter { it.fileName.toString() != "memory.txt" }
						.flatMap { path -> splitFile(path).stream() }
						.toList()
				}
			}
		}
		if (!initialLoad.isCompleted) {
			initialLoad.complete(Unit)
		}
		println("RAG loaded ${chunks.size} chunk(s) from ${config.knowledgeDir}")
	}

	private fun search(question: String, groupId: Long?): List<RAGMatch> {
		val scopedChunks = chunks.filter { it.groupId == null || it.groupId == groupId }
		return keywordSearch(question, scopedChunks)
	}

	private fun keywordSearch(question: String, scopedChunks: List<RAGChunk>): List<RAGMatch> {
		val queryTokens = tokenize(question)
		if (queryTokens.isEmpty()) {
			return emptyList()
		}
		return scopedChunks.asSequence()
			.mapNotNull { chunk ->
				val score = keywordScore(chunk, queryTokens, question)
				if (score >= config.keywordMinScore) RAGMatch(chunk, score, "keyword") else null
			}
			.sortedByDescending { it.score }
			.take(config.topK)
			.toList()
	}

	private fun splitFile(path: Path): List<RAGChunk> {
		val text = runCatching { Files.readString(path, StandardCharsets.UTF_8) }.getOrDefault("")
		if (text.isBlank()) {
			return emptyList()
		}
		val source = config.knowledgeDir.relativize(path).toString()
		val groupId = groupIdForSource(source)
		val title = findTitle(text) ?: path.fileName.toString()
		val paragraphs = text
			.replace("\r\n", "\n")
			.split(Regex("\n\\s*\n"))
			.map { it.trim() }
			.filter { it.isNotBlank() }

		val result = mutableListOf<RAGChunk>()
		val current = StringBuilder()
		fun flush() {
			if (current.isNotBlank()) {
				result.add(RAGChunk("$source#${result.size + 1}", source, title, current.toString().trim(), groupId = groupId))
				current.clear()
			}
		}
		for (paragraph in paragraphs) {
			if (current.length + paragraph.length + 2 > config.chunkSize) {
				flush()
			}
			if (paragraph.length > config.chunkSize) {
				var offset = 0
				while (offset < paragraph.length) {
					val end = min(offset + config.chunkSize, paragraph.length)
					result.add(RAGChunk("$source#${result.size + 1}", source, title, paragraph.substring(offset, end), groupId = groupId))
					offset = end
				}
			} else {
				if (current.isNotEmpty()) current.append("\n\n")
				current.append(paragraph)
			}
		}
		flush()
		return result
	}

	private fun keywordScore(chunk: RAGChunk, queryTokens: Set<String>, question: String): Double {
		val titleLower = chunk.title.lowercase(Locale.ROOT)
		val contentLower = chunk.content.lowercase(Locale.ROOT)
		var score = 0.0
		queryTokens.forEach { token ->
			if (titleLower.contains(token)) {
				score += if (token.length >= 3) 4.0 else 2.0
			} else if (contentLower.contains(token)) {
				score += if (token.length >= 3) 2.0 else 1.0
			}
		}
		val normalizedQuestion = question.trim().lowercase(Locale.ROOT)
		if (normalizedQuestion.length >= 4) {
			if (titleLower.contains(normalizedQuestion)) {
				score += 12.0
			} else if (contentLower.contains(normalizedQuestion)) {
				score += 8.0
			}
		}
		return score
	}

	private fun tokenize(input: String): Set<String> {
		val normalized = input.lowercase(Locale.ROOT)
		val asciiTokens = normalized
			.split(Regex("[^\\p{L}\\p{N}_]+"))
			.map { it.trim() }
			.filter { it.length >= 2 }
		val cjkChars = normalized
			.filter { Character.UnicodeScript.of(it.code) in cjkScripts }
		val cjkTokens = if (cjkChars.length == 1) {
			listOf(cjkChars)
		} else {
			cjkChars.windowed(2)
		}
		return (asciiTokens + cjkTokens).toSet()
	}

	private fun findTitle(text: String): String? {
		return text.lineSequence()
			.map { it.trim() }
			.firstOrNull { it.startsWith("#") }
			?.trimStart('#')
			?.trim()
			?.takeIf { it.isNotBlank() }
	}

	private suspend fun awaitInitialLoad() {
		ensureInitialLoadScheduled()
		initialLoad.await()
	}

	private fun ensureInitialLoadScheduled() {
		if (!initialLoadStarted.compareAndSet(false, true)) {
			return
		}
		initializationScope.launch {
			runCatching {
				reload()
			}.onFailure {
				println("RAG load failed: ${it.message}")
				if (!initialLoad.isCompleted) {
					initialLoad.complete(Unit)
				}
			}
		}
	}

	private data class RAGChunk(
		val id: String,
		val source: String,
		val title: String,
		val content: String,
		val groupId: Long? = null,
	)

	private data class RAGMatch(
		val chunk: RAGChunk,
		val score: Double,
		val strategy: String,
	)

	private companion object {
		private val dotEnvMap: Map<String, String> by lazy { loadDotEnv() }

		private fun loadDotEnv(): Map<String, String> {
			val candidates = listOf(
				Path.of(".env"),
				Path.of("QAPI3/.env"),
				Path.of("../.env"),
			)
			val map = mutableMapOf<String, String>()
			for (path in candidates) {
				if (Files.isRegularFile(path)) {
					runCatching {
						Files.readAllLines(path, StandardCharsets.UTF_8).forEach { rawLine ->
							val line = rawLine.trim()
							if (line.isEmpty() || line.startsWith("#")) return@forEach
							val separatorIndex = line.indexOf('=')
							if (separatorIndex > 0) {
								val key = line.substring(0, separatorIndex).trim()
								var value = line.substring(separatorIndex + 1).trim()
								if ((value.startsWith("\"") && value.endsWith("\"")) ||
									(value.startsWith("'") && value.endsWith("'"))
								) {
									value = value.substring(1, value.length - 1)
								}
								map.putIfAbsent(key, value)
							}
						}
					}
					break
				}
			}
			return map
		}

		private fun readEnv(name: String): String? =
			System.getenv(name)?.trim()?.takeIf { it.isNotBlank() } ?: dotEnvMap[name]?.trim()?.takeIf { it.isNotBlank() }

		private fun readString(name: String, defaultValue: String): String =
			readEnv(name) ?: defaultValue

		private fun readInt(name: String, defaultValue: Int): Int =
			readEnv(name)?.toIntOrNull() ?: defaultValue

		private fun readDouble(name: String, defaultValue: Double): Double =
			readEnv(name)?.toDoubleOrNull() ?: defaultValue

		private fun readBoolean(name: String, defaultValue: Boolean): Boolean {
			val value = readEnv(name)?.lowercase(Locale.ROOT) ?: return defaultValue
			return when (value) {
				"true", "1", "yes", "on" -> true
				"false", "0", "no", "off" -> false
				else -> defaultValue
			}
		}

		private fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
			install(HttpTimeout) {
				requestTimeoutMillis = 3_000
				connectTimeoutMillis = 1_000
				socketTimeoutMillis = 3_000
			}
		}

		private fun groupIdForSource(source: String): Long? {
			val normalized = source.replace('\\', '/')
			val parts = normalized.split('/')
			if (parts.size >= 2) {
				parts[0].toLongOrNull()?.let { return it }
			}
			if (parts.size >= 3 && parts[0] == "groups") {
				return parts[1].toLongOrNull()
			}
			return null
		}

		private val cjkScripts = setOf(
			Character.UnicodeScript.HAN,
			Character.UnicodeScript.HIRAGANA,
			Character.UnicodeScript.KATAKANA,
			Character.UnicodeScript.HANGUL,
		)
	}

	private fun defaultConfig(): Config = Config(
		enabled = readBoolean("RAG_ENABLED", true),
		knowledgeDir = Path.of(readString("RAG_KNOWLEDGE_DIR", "data/llm/rag")),
		topK = readInt("RAG_TOP_K", 5).coerceIn(1, 12),
		maxContextChars = readInt("RAG_MAX_CONTEXT_CHARS", 4000).coerceAtLeast(500),
		chunkSize = readInt("RAG_CHUNK_SIZE", 900).coerceAtLeast(200),
		keywordMinScore = readDouble("RAG_KEYWORD_MIN_SCORE", 2.0),
		enableMnemos = readBoolean("ENABLE_MNEMOS", false) || readBoolean("MNEMOS_ENABLED", false),
		mnemosEndpoint = readString("MNEMOS_ENDPOINT", readString("MNEMOS_URL", "http://127.0.0.1:8089")),
	)
}
