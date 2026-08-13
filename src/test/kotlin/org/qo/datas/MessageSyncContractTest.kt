package org.qo.datas

import com.google.gson.JsonParser
import org.qo.services.messageServices.Msg
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageSyncContractTest {
    private val nodes = Nodes().apply {
        nodesData = listOf(Node("QQ", 0, Role.SERVER, "secret"))
    }

    @BeforeTest
    fun clearQueue() {
        Msg.msgQueue.clear()
    }

    @AfterTest
    fun cleanupQueue() {
        Msg.msgQueue.clear()
    }

    @Test
    fun `qbot messages retain stable ids through api download payload`() {
        val first = upload("qq:946085440:1", "same", 1000)
        val second = upload("qq:946085440:2", "same", 1000)

        assertTrue(nodes.validate_message(first))
        assertTrue(nodes.validate_message(second))

        val messages = Msg.get().getAsJsonArray("messages")
        assertEquals(2, messages.size())
        assertEquals("qq:946085440:1", messages[0].asJsonObject.get("id").asString)
        assertEquals("qq:946085440:2", messages[1].asJsonObject.get("id").asString)
        assertEquals(false, messages[0].asJsonObject.has("token"))
    }

    private fun upload(id: String, message: String, time: Long): String = JsonParser.parseString(
        """{"id":"$id","message":"$message","from":0,"token":"secret","type":"qq_chat","time":$time,"sender":"10001"}"""
    ).toString()
}
