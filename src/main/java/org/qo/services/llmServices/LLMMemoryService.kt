package org.qo.services.llmServices

import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@Service
class LLMMemoryService(private val ragService: RAGService) {
	fun insertMemory(content: String, groupId: Long): Boolean {
		val memoryPath = Path.of("data/llm/rag/groups/$groupId/memory.txt")
		val normalized = content.trim()
		if (normalized.isBlank()) {
			return false
		}

		Files.createDirectories(memoryPath.parent)
		Files.writeString(
			memoryPath,
			"- $normalized\n",
			StandardOpenOption.CREATE,
			StandardOpenOption.APPEND,
		)
		ragService.reload()
		return true
	}
}
