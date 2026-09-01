package org.qo.services.llmServices

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.qo.services.llmServices.tools.GetQoPlayerProfileTool
import org.qo.utils.UserProcess
import reactor.core.publisher.Mono
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GetQoPlayerProfileToolTest {
    private val userProcess = Mockito.mock(UserProcess::class.java)
    private val tool = GetQoPlayerProfileTool(userProcess)
    private val context = LLMToolContext(null, "10001", "tester")

    @Test
    fun `returns found only for an existing qo player`() = runBlocking {
        Mockito.`when`(userProcess.queryReg("glowingstone124"))
            .thenReturn(Mono.just("""{"code":0,"affiliated":false,"qq":1294915648}"""))

        val result = execute("glowingstone124")

        assertTrue(result.get("found").asBoolean)
        assertTrue(result.get("username").asString == "glowingstone124")
    }

    @Test
    fun `does not expose a card for missing or affiliated players`() = runBlocking {
        Mockito.`when`(userProcess.queryReg("MissingPlayer"))
            .thenReturn(Mono.just("""{"code":1,"qq":-1}"""))
        Mockito.`when`(userProcess.queryReg("RemotePlayer"))
            .thenReturn(Mono.just("""{"affiliated":true,"host":"remote"}"""))

        assertFalse(execute("MissingPlayer").get("found").asBoolean)
        assertFalse(execute("RemotePlayer").get("found").asBoolean)
    }

    private suspend fun execute(username: String): JsonObject = JsonParser.parseString(
        tool.execute(JsonObject().apply { addProperty("username", username) }, context),
    ).asJsonObject
}
