package org.qo.services.llmServices

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RAGServiceTest {
	@TempDir
	lateinit var tempDir: Path

	@Test
	fun `buildContext loads markdown content without external models`() = runBlocking {
		Files.writeString(tempDir.resolve("rules.md"), "# 主城规则\n主城禁止破坏公共建筑。")
		val service = service()
		try {
			service.reload()
			val context = service.buildContext("主城可以破坏吗？")

			assertNotNull(context)
			assertTrue(context.contains("主城规则"))
			assertTrue(context.contains("主城禁止破坏公共建筑"))
			assertTrue(context.contains("检索：keyword"))
		} finally {
			service.shutdown()
		}
	}

	@Test
	fun `buildContext filters content by group id scope`() = runBlocking {
		val groupDir = tempDir.resolve("123456")
		Files.createDirectories(groupDir)
		Files.writeString(groupDir.resolve("group_rule.md"), "# 群专有规则\n本群专有福利为每日签到积分翻倍。")

		val service = service()
		try {
			service.reload()

			val matchedContext = service.buildContext("福利如何签到？", groupId = 123456)
			assertNotNull(matchedContext)
			assertTrue(matchedContext.contains("群专有规则"))

			val otherGroupContext = service.buildContext("福利如何签到？", groupId = 999999)
			assertNull(otherGroupContext)
		} finally {
			service.shutdown()
		}
	}

	private fun service(
		keywordMinScore: Double = 1.0,
	): RAGService = RAGService(
		RAGService.Config(
			enabled = true,
			knowledgeDir = tempDir,
			topK = 5,
			maxContextChars = 4000,
			chunkSize = 2000,
			keywordMinScore = keywordMinScore,
		),
	)
}
