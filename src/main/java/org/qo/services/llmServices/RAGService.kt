package org.qo.services.llmServices

import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.math.min
import kotlin.math.sqrt

@Service
class RAGService {
	private val enabled = readBoolean("RAG_ENABLED", true)
	private val knowledgeDir = Path.of(System.getenv("RAG_KNOWLEDGE_DIR") ?: "data/llm/rag")
	private val topK = readInt("RAG_TOP_K", 5).coerceIn(1, 12)
	private val maxContextChars = readInt("RAG_MAX_CONTEXT_CHARS", 4000).coerceAtLeast(500)
	private val chunkSize = readInt("RAG_CHUNK_SIZE", 900).coerceAtLeast(200)
	private val embeddingEnabled = readBoolean("RAG_EMBEDDING_ENABLED", true)
	private val embeddingUrl = System.getenv("RAG_EMBEDDING_API_URL")
		?: System.getenv("LLM_EMBEDDING_API_URL")
		?: "https://api.openai.com/v1/embeddings"
	private val embeddingModel = System.getenv("RAG_EMBEDDING_MODEL")
		?: System.getenv("LLM_EMBEDDING_MODEL")
		?: "text-embedding-3-small"
	private val embeddingToken = System.getenv("RAG_EMBEDDING_API_TOKEN")
		?: System.getenv("LLM_API_TOKEN")
		?: runCatching { Files.readString(Path.of("LLMAPITOKEN")).trim() }.getOrDefault("")
	private val embeddingCache = ConcurrentHashMap<String, List<Double>>()
	private val jsonParser = JsonParser()
	private val client = HttpClient(CIO) {
		install(HttpTimeout) {
			requestTimeoutMillis = 60_000
			socketTimeoutMillis = 60_000
			connectTimeoutMillis = 10_000
		}
	}
	@Volatile
	private var chunks: List<RAGChunk> = emptyList()

	@PostConstruct
	fun init() {
		reload()
	}

	fun buildContext(question: String): String? {
		if (!enabled || question.isBlank()) {
			return null
		}
		val matches = runBlocking { search(question) }
		if (matches.isEmpty()) {
			return null
		}
		val sb = StringBuilder()
		sb.append("知识库资料如下。优先根据这些资料回答；资料未覆盖时，明确说明不确定，不要编造服务器规则。\n")
		var used = 0
		matches.forEachIndexed { index, match ->
			val content = match.chunk.content.trim()
			val remaining = maxContextChars - used
			if (remaining <= 0) return@forEachIndexed
			val clipped = content.take(remaining)
			used += clipped.length
			sb.append("\n[").append(index + 1).append("] ")
				.append("来源：").append(match.chunk.source)
				.append("；标题：").append(match.chunk.title)
				.append("；检索：").append(match.strategy)
				.append("；相关度：").append("%.3f".format(Locale.ROOT, match.score))
				.append("\n")
				.append(clipped)
				.append("\n")
		}
		return sb.toString()
	}

	fun reload() {
		chunks = if (!enabled || !Files.isDirectory(knowledgeDir)) {
			emptyList()
		} else {
			val loaded = Files.walk(knowledgeDir).use { stream ->
				stream
					.filter { it.isRegularFile() }
					.filter { it.extension.lowercase(Locale.ROOT) in setOf("md", "txt") }
					.flatMap { path -> splitFile(path).stream() }
					.toList()
			}
			if (embeddingEnabled && embeddingToken.isNotBlank()) {
				runBlocking {
					loaded.map { chunk ->
						chunk.copy(embedding = embeddingFor(chunk.content))
					}
				}
			} else {
				loaded
			}
		}
		val embedded = chunks.count { it.embedding != null }
		println("RAG loaded ${chunks.size} chunk(s) from $knowledgeDir; embedded=$embedded")
	}

	private suspend fun search(question: String): List<RAGMatch> {
		val queryEmbedding = if (embeddingEnabled && embeddingToken.isNotBlank()) {
			embeddingFor(question)
		} else {
			null
		}
		if (queryEmbedding != null && chunks.any { it.embedding != null }) {
			val vectorMatches = chunks.asSequence()
				.mapNotNull { chunk ->
					val embedding = chunk.embedding ?: return@mapNotNull null
					val score = cosine(queryEmbedding, embedding)
					if (score > 0.0) RAGMatch(chunk, score, "embedding") else null
				}
				.sortedByDescending { it.score }
				.take(topK)
				.toList()
			if (vectorMatches.isNotEmpty()) {
				return vectorMatches
			}
		}
		return keywordSearch(question)
	}

	private fun keywordSearch(question: String): List<RAGMatch> {
		val queryTokens = tokenize(question)
		if (queryTokens.isEmpty()) {
			return emptyList()
		}
		return chunks.asSequence()
			.mapNotNull { chunk ->
				val score = keywordScore(chunk, queryTokens, question)
				if (score > 0.0) RAGMatch(chunk, score, "keyword") else null
			}
			.sortedByDescending { it.score }
			.take(topK)
			.toList()
	}

	private suspend fun embeddingFor(input: String): List<Double>? {
		val text = input.trim()
		if (text.isBlank()) {
			return null
		}
		embeddingCache[text]?.let { return it }
		return withContext(Dispatchers.IO) {
			runCatching {
				val response = client.post(embeddingUrl) {
					header(HttpHeaders.Authorization, "Bearer $embeddingToken")
					contentType(ContentType.Application.Json)
					setBody("""{"model":${quote(embeddingModel)},"input":${quote(text)}}""")
				}
				val body = response.bodyAsText()
				val embedding = jsonParser.parse(body)
					.asJsonObject
					.getAsJsonArray("data")[0]
					.asJsonObject
					.getAsJsonArray("embedding")
					.map { it.asDouble }
				embeddingCache[text] = embedding
				embedding
			}.onFailure {
				println("RAG embedding failed: ${it.message}; fallback to keyword search")
			}.getOrNull()
		}
	}

	private fun splitFile(path: Path): List<RAGChunk> {
		val text = runCatching { Files.readString(path, StandardCharsets.UTF_8) }.getOrDefault("")
		if (text.isBlank()) {
			return emptyList()
		}
		val source = knowledgeDir.relativize(path).toString()
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
				result.add(RAGChunk("$source#${result.size + 1}", source, title, current.toString().trim()))
				current.clear()
			}
		}
		for (paragraph in paragraphs) {
			if (current.length + paragraph.length + 2 > chunkSize) {
				flush()
			}
			if (paragraph.length > chunkSize) {
				var offset = 0
				while (offset < paragraph.length) {
					val end = min(offset + chunkSize, paragraph.length)
					result.add(RAGChunk("$source#${result.size + 1}", source, title, paragraph.substring(offset, end)))
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
		val haystack = (chunk.title + "\n" + chunk.content).lowercase(Locale.ROOT)
		var score = 0.0
		queryTokens.forEach { token ->
			if (haystack.contains(token)) {
				score += if (token.length >= 3) 2.0 else 1.0
			}
		}
		val normalizedQuestion = question.trim().lowercase(Locale.ROOT)
		if (normalizedQuestion.length >= 4 && haystack.contains(normalizedQuestion)) {
			score += 8.0
		}
		return score
	}

	private fun tokenize(input: String): Set<String> {
		val normalized = input.lowercase(Locale.ROOT)
		val asciiTokens = normalized
			.split(Regex("[^\\p{L}\\p{N}_]+"))
			.map { it.trim() }
			.filter { it.length >= 2 }
		val cjkBigrams = normalized
			.filter { Character.UnicodeScript.of(it.code) in cjkScripts }
			.windowed(2)
		return (asciiTokens + cjkBigrams).toSet()
	}

	private fun cosine(left: List<Double>, right: List<Double>): Double {
		if (left.size != right.size || left.isEmpty()) {
			return 0.0
		}
		var dot = 0.0
		var leftNorm = 0.0
		var rightNorm = 0.0
		for (i in left.indices) {
			dot += left[i] * right[i]
			leftNorm += left[i] * left[i]
			rightNorm += right[i] * right[i]
		}
		if (leftNorm == 0.0 || rightNorm == 0.0) {
			return 0.0
		}
		return dot / (sqrt(leftNorm) * sqrt(rightNorm))
	}

	private fun findTitle(text: String): String? {
		return text.lineSequence()
			.map { it.trim() }
			.firstOrNull { it.startsWith("#") }
			?.trimStart('#')
			?.trim()
			?.takeIf { it.isNotBlank() }
	}

	private fun quote(value: String): String = com.google.gson.JsonObject()
		.apply { addProperty("value", value) }
		.get("value")
		.toString()

	private fun readInt(name: String, defaultValue: Int): Int =
		System.getenv(name)?.trim()?.toIntOrNull() ?: defaultValue

	private fun readBoolean(name: String, defaultValue: Boolean): Boolean =
		when (System.getenv(name)?.trim()?.lowercase(Locale.ROOT)) {
			"1", "true", "yes", "on" -> true
			"0", "false", "no", "off" -> false
			else -> defaultValue
		}

	private data class RAGChunk(
		val id: String,
		val source: String,
		val title: String,
		val content: String,
		val embedding: List<Double>? = null,
	)

	private data class RAGMatch(
		val chunk: RAGChunk,
		val score: Double,
		val strategy: String,
	)

	private companion object {
		private val cjkScripts = setOf(
			Character.UnicodeScript.HAN,
			Character.UnicodeScript.HIRAGANA,
			Character.UnicodeScript.KATAKANA,
			Character.UnicodeScript.HANGUL,
		)
	}
}
