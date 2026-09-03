package org.qo.services.gameStatusService

import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.qo.datas.Nodes
import org.qo.orm.UserORM
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StatusTest {

    @Test
    fun testInitialSnapshotInSseStream() = runTest {
        val userORM = Mockito.mock(UserORM::class.java)
        val nodes = Mockito.mock(Nodes::class.java)
        Mockito.`when`(userORM.countAsync()).thenReturn(150L)

        val status = Status(userORM = userORM, nodes = nodes, scope = this)

        // Pre-populate server 1 status
        val initialJson = JsonObject().apply {
            addProperty("onlinecount", 5)
            addProperty("mspt", 18.5)
        }
        status.statusMap[1] = initialJson

        // Connect to stream for server 1
        val firstEvent = status.streamStatus(id = 1).first()

        assertNotNull(firstEvent.data())
        assertTrue(firstEvent.data()!!.contains("\"onlinecount\":5"))
        assertTrue(firstEvent.data()!!.contains("\"totalcount\":150"))
    }

    @Test
    fun testLiveUpdatesBroadcastToSubscribers() = runTest {
        val userORM = Mockito.mock(UserORM::class.java)
        val nodes = Mockito.mock(Nodes::class.java)
        Mockito.`when`(userORM.countAsync()).thenReturn(200L)
        Mockito.`when`(nodes.getServerFromToken("valid-token")).thenReturn(1)

        val status = Status(userORM = userORM, nodes = nodes, scope = this)

        // Collect 2 events: initial snapshot + 1 live update
        val events = mutableListOf<String>()
        val job = launch {
            status.streamStatus(id = 1).take(2).collect {
                it.data()?.let { d -> events.add(d) }
            }
        }

        // Allow collector to subscribe and receive initial snapshot
        delay(50)

        // Upload new status
        val uploadJson = "{\"onlinecount\":12,\"mspt\":22.0}"
        status.upload(uploadJson, "valid-token")

        job.join()

        assertEquals(2, events.size)
        assertTrue(events[1].contains("\"onlinecount\":12"))
        assertTrue(events[1].contains("\"totalcount\":200"))
    }

    @Test
    fun testServerIdFiltering() = runTest {
        val userORM = Mockito.mock(UserORM::class.java)
        val nodes = Mockito.mock(Nodes::class.java)
        Mockito.`when`(userORM.countAsync()).thenReturn(100L)
        Mockito.`when`(nodes.getServerFromToken("server4-token")).thenReturn(4)
        Mockito.`when`(nodes.getServerFromToken("server1-token")).thenReturn(1)

        val status = Status(userORM = userORM, nodes = nodes, scope = this)

        val eventsForServer1 = mutableListOf<String>()
        val job = launch {
            status.streamStatus(id = 1).take(2).collect {
                it.data()?.let { d -> eventsForServer1.add(d) }
            }
        }

        delay(50)

        // Upload to server 4 (creative) -> should NOT be received by server 1 subscriber
        status.upload("{\"onlinecount\":99,\"server\":\"creative\"}", "server4-token")
        delay(50)

        // Now upload to server 1 (survival) -> SHOULD be received
        status.upload("{\"onlinecount\":7,\"server\":\"survival\"}", "server1-token")

        job.join()

        assertEquals(2, eventsForServer1.size)
        assertTrue(eventsForServer1[1].contains("\"server\":\"survival\""))
    }

    @Test
    fun testCustomEventName() = runTest {
        val userORM = Mockito.mock(UserORM::class.java)
        val nodes = Mockito.mock(Nodes::class.java)
        Mockito.`when`(userORM.countAsync()).thenReturn(100L)

        val status = Status(userORM = userORM, nodes = nodes, scope = this)
        val firstEvent = status.streamStatus(id = 1, eventName = "status").first()

        assertEquals("status", firstEvent.event())
    }
}
