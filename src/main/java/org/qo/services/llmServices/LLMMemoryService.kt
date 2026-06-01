package org.qo.services.llmServices

import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@Service
class LLMMemoryService(private val ragService: RAGService) {
	fun insertMemory(content: String, groupId: Long): Boolean {
		val memoryPath = ragService.groupMemoryPath(groupId)
		val normalized = normalizeMemory(content)
		if (normalized.isBlank()) {
			return false
		}

		synchronized(writeLock) {
			val existing = readMemoryItems(memoryPath)
			if (normalized in existing) {
				return false
			}
			Files.createDirectories(memoryPath.parent)
			Files.writeString(
				memoryPath,
				"- $normalized\n",
				StandardOpenOption.CREATE,
				StandardOpenOption.APPEND,
			)
		}
		ragService.reload()
		return true
	}

	private fun normalizeMemory(content: String): String =
		content
			.lineSequence()
			.map { it.trim().trimStart('-', '*').trim() }
			.filter { it.isNotBlank() }
			.joinToString(" ")
			.replace(Regex("\\s+"), " ")
			.take(2000)

	private fun readMemoryItems(memoryPath: Path): Set<String> {
		if (!Files.isRegularFile(memoryPath)) {
			return emptySet()
		}
		return runCatching {
			Files.readAllLines(memoryPath)
				.asSequence()
				.map { normalizeMemory(it) }
				.filter { it.isNotBlank() }
				.toSet()
		}.getOrDefault(emptySet())
	}

	private companion object {
		private val writeLock = Any()
	}
}
