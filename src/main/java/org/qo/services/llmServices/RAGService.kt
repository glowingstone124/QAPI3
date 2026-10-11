package org.qo.services.llmServices

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
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
	)

	private var config = defaultConfig()
	private val initializationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val initialLoadStarted = AtomicBoolean(false)
	private val initialLoad = CompletableDeferred<Unit>()
	@Volatile
	private var chunks: List<RAGChunk> = emptyList()

	internal constructor(config: Config) : this() {
		this.config = config
	}

	@PostConstruct
	fun init() {
		ensureInitialLoadScheduled()
	}

	@PreDestroy
	fun shutdown() {
		initializationScope.cancel()
	}

	suspend fun buildContext(question: String, groupId: Long? = null): String? {
		if (!config.enabled || question.isBlank()) {
			return null
		}
		awaitInitialLoad()
		val matches = search(question, groupId)
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

	suspend fun reload() {
		initialLoadStarted.set(true)
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

	private fun readInt(name: String, defaultValue: Int): Int =
		System.getenv(name)?.trim()?.toIntOrNull() ?: defaultValue

	private fun readDouble(name: String, defaultValue: Double): Double =
		System.getenv(name)?.trim()?.toDoubleOrNull() ?: defaultValue

	private fun readBoolean(name: String, defaultValue: Boolean): Boolean =
		System.getenv(name)?.trim()?.lowercase(Locale.ROOT)?.toBooleanStrictOrNull() ?: defaultValue

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
		knowledgeDir = Path.of(System.getenv("RAG_KNOWLEDGE_DIR") ?: "data/llm/rag"),
		topK = readInt("RAG_TOP_K", 5).coerceIn(1, 12),
		maxContextChars = readInt("RAG_MAX_CONTEXT_CHARS", 4000).coerceAtLeast(500),
		chunkSize = readInt("RAG_CHUNK_SIZE", 900).coerceAtLeast(200),
		keywordMinScore = readDouble("RAG_KEYWORD_MIN_SCORE", 2.0),
	)
}
