package org.qo.services.authCentral

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.qo.datas.Mapping.Users
import org.qo.datas.Node
import org.qo.datas.Nodes
import org.qo.datas.Role
import org.qo.db.repository.UserDbRepository
import org.qo.orm.LoginToken
import org.qo.orm.LoginTokenORM
import org.qo.redis.Configuration
import org.qo.services.loginService.Login
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.*

class GameLoginServiceTest {
    private var redisBefore = false
    private lateinit var store: GameLoginTicketStore
    private lateinit var login: Login
    private lateinit var tokens: LoginTokenORM
    private lateinit var users: UserDbRepository
    private lateinit var nodes: Nodes
    private lateinit var service: GameLoginService
    private val connection = GameConnectionBody("Alex", "00112233-4455-6677-8899-aabbccddeeff", "A".repeat(43))

    @BeforeEach fun setup() = runBlocking {
        redisBefore = Configuration.EnableRedis
        Configuration.EnableRedis = false
        store = GameLoginTicketStore()
        login = Mockito.mock(Login::class.java)
        tokens = Mockito.mock(LoginTokenORM::class.java)
        users = Mockito.mock(UserDbRepository::class.java)
        nodes = Mockito.mock(Nodes::class.java)
        Mockito.`when`(login.loginTokenORM).thenReturn(tokens)
        Mockito.`when`(tokens.read("session")).thenReturn(LoginToken("session", "Alex", System.currentTimeMillis() + 60_000, "qmod"))
        Mockito.`when`(users.readAsync("Alex")).thenReturn(Users(username = "Alex", uid = 10))
        Mockito.`when`(nodes.getNodeFromToken("server-one")).thenReturn(Node("one", 1, Role.SERVER, "server-one"))
        Mockito.`when`(nodes.getNodeFromToken("server-two")).thenReturn(Node("two", 2, Role.SERVER, "server-two"))
        service = GameLoginService(login, users, nodes, store)
    }
    @AfterEach fun cleanup() { Configuration.EnableRedis = redisBefore }
    private fun challenge(): GameConnectionBody {
        val result = service.challenge("server-one", connection)
        assertTrue(result.ok)
        return connection.copy(nodeId = result.nodeId!!, challenge = result.challenge!!)
    }
    private suspend fun proof(): GameConnectionBody {
        val bound = challenge()
        val approved = service.approve("session", bound)
        assertTrue(approved.ok)
        assertEquals(30, approved.expiresIn)
        return bound.copy(ticket = approved.ticket!!)
    }

    @Test fun `valid handshake consumes both challenge and proof exactly once`() = runBlocking {
        val bound = challenge()
        val grant = service.approve("session", bound)
        assertTrue(grant.ok)
        assertFalse(service.approve("session", bound).ok)
        val body = bound.copy(ticket = grant.ticket!!)
        val result = service.redeem("server-one", body)
        assertTrue(result.ok)
        assertEquals("Alex", result.username)
        assertNull(result.ticket)
        assertFalse(service.redeem("server-one", body).ok)
    }
    @Test fun `proof is bound to server nonce uuid username and challenge`() = runBlocking {
        val proof = proof()
        assertFalse(service.redeem("server-two", proof).ok)
        assertFalse(service.redeem("server-one", proof.copy(nonce = "B".repeat(43))).ok)
        assertFalse(service.redeem("server-one", proof.copy(playerUuid = "11112233-4455-6677-8899-aabbccddeeff")).ok)
        assertFalse(service.redeem("server-one", proof.copy(username = "Steve")).ok)
        assertFalse(service.redeem("server-one", proof.copy(challenge = "GC-" + "C".repeat(43))).ok)
        assertTrue(service.redeem("server-one", proof).ok)
    }
    @Test fun `only qmod sessions for the named unfrozen account can approve`() = runBlocking {
        val bound = challenge()
        assertFalse(service.approve(null, bound).ok)
        Mockito.`when`(tokens.read("session")).thenReturn(LoginToken("session", "Alex", Long.MAX_VALUE, "game"))
        assertFalse(service.approve("session", bound).ok)
        Mockito.`when`(tokens.read("session")).thenReturn(LoginToken("session", "Alex", 0, "qmod"))
        assertFalse(service.approve("session", bound).ok)
        Mockito.`when`(tokens.read("session")).thenReturn(LoginToken("session", "Alex", Long.MAX_VALUE, "qmod"))
        Mockito.`when`(users.readAsync("Alex")).thenReturn(Users(username = "Alex", frozen = true))
        assertFalse(service.approve("session", bound).ok)
        Mockito.`when`(users.readAsync("Alex")).thenReturn(Users(username = "Steve"))
        assertFalse(service.approve("session", bound).ok)
        Mockito.`when`(users.readAsync("Alex")).thenReturn(Users(username = "Alex"))
        assertTrue(service.approve("session", bound).ok)
    }
    @Test fun `freezing or deleting an account before redemption prevents authentication`() = runBlocking {
        val proof = proof()
        Mockito.`when`(users.readAsync("Alex")).thenReturn(Users(username = "Alex", frozen = true))
        assertFalse(service.redeem("server-one", proof).ok)
        Mockito.`when`(users.readAsync("Alex")).thenReturn(Users(username = "Alex"))
        assertFalse(service.redeem("server-one", proof).ok)
    }
    @Test fun `linked qq qmod session resolves current account`() = runBlocking {
        Mockito.`when`(tokens.read("session")).thenReturn(LoginToken("session", "qq:10", Long.MAX_VALUE, "qmod"))
        Mockito.`when`(users.readAsync(10L)).thenReturn(Users(username = "Alex", uid = 10))
        assertTrue(service.redeem("server-one", proof()).ok)
    }
    @Test fun `unauthorized non-server nodes and malformed bindings fail closed`() {
        assertFalse(service.challenge(null, connection).ok)
        Mockito.`when`(nodes.getNodeFromToken("proxy")).thenReturn(Node("proxy", 3, Role.CHILD_NODE, "proxy"))
        assertFalse(service.challenge("proxy", connection).ok)
        assertFalse(service.challenge("server-one", connection.copy(username = "../Alex")).ok)
        assertFalse(service.challenge("server-one", connection.copy(playerUuid = "1-1-1-1-1")).ok)
        assertFalse(service.challenge("server-one", connection.copy(nonce = "short")).ok)
    }
    @Test fun `concurrent redemption has a single winner`() = runBlocking {
        val proof = proof()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll(List(16) { Callable { runBlocking { service.redeem("server-one", proof).ok } } })
            assertEquals(1, results.count { it.get() })
        } finally { pool.shutdownNow() }
    }
    @Test fun `pre-auth challenges cannot be redeemed as game proofs or web SSO tickets`() = runBlocking {
        val bound = challenge()
        assertFalse(service.redeem("server-one", bound.copy(ticket = bound.challenge)).ok)
        val sso = SsoTicketStore()
        val binding = "qmod-game:challenge:v1:1:${bound.playerUuid}:alex:${bound.nonce}"
        assertNull(sso.validateAndConsume(bound.challenge, binding))
        val proof = service.approve("session", bound).ticket!!
        assertNull(sso.validateAndConsume(proof, "qmod-game:proof:v1:1:${bound.playerUuid}:alex:${bound.nonce}:${bound.challenge}"))
        assertTrue(service.redeem("server-one", bound.copy(ticket = proof)).ok)
    }
    @Test fun `controller sends no-store and appropriate error status`() = runBlocking {
        val controller = GameLoginController(service)
        assertEquals(HttpStatus.UNAUTHORIZED, controller.challenge(null, connection).statusCode)
        val bound = challenge()
        val result = controller.approve("Bearer session", bound)
        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals("no-store", result.headers.cacheControl)
        assertEquals(HttpStatus.BAD_REQUEST, controller.challenge("server-one", connection.copy(nonce = "x")).statusCode)
    }
    @Test fun `http requests reject explicit null fields before executing the handshake`() {
        val client = WebTestClient.bindToController(GameLoginController(service)).build()
        val body = JsonObject().apply {
            addProperty("username", connection.username)
            addProperty("playerUuid", connection.playerUuid)
            addProperty("nonce", connection.nonce)
            addProperty("nodeId", 1)
            addProperty("challenge", "GC-" + "C".repeat(43))
            addProperty("ticket", "GP-" + "D".repeat(43))
        }
        for (route in listOf("challenge", "approve", "redeem")) {
            for (field in listOf("username", "playerUuid", "nonce", "nodeId", "challenge", "ticket")) {
                val invalid = body.deepCopy().apply { add(field, JsonNull.INSTANCE) }
                client.post().uri("/qo/auth/game/$route")
                    .header("Token", "server-one")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(invalid.toString())
                    .exchange().expectStatus().isBadRequest
            }
        }
        client.post().uri("/qo/auth/game/challenge")
            .header("Token", "server-one")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body.toString())
            .exchange().expectStatus().isOk
    }
    @Test fun `short lived store records expire and cannot be revived`() {
        val record = store.createProof("Alex", "game")
        assertTrue(record.expiresAt - record.createdAt == 30_000L)
        val field = GameLoginTicketStore::class.java.getDeclaredField("localStore").also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST") val records = field.get(store) as MutableMap<String, GameLoginTicketRecord>
        records[record.ticket] = record.copy(expiresAt = 0)
        assertNull(store.validateAndConsume(record.ticket, "game"))
        assertNull(store.validateAndConsume(record.ticket, "game"))
    }
}
