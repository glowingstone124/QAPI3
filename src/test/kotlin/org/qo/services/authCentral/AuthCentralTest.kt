package org.qo.services.authCentral

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.qo.datas.Mapping.Users
import org.qo.db.repository.UserDbRepository
import org.qo.redis.Configuration
import org.qo.orm.LoginToken
import org.qo.services.loginService.Login
import org.springframework.http.HttpStatus
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthCentralTest {
	private var previousRedisState = true

	@BeforeEach
	fun setUp() {
		previousRedisState = Configuration.EnableRedis
		Configuration.EnableRedis = false
	}

	@AfterEach
	fun tearDown() {
		Configuration.EnableRedis = previousRedisState
	}

	@Test
	fun `service registry validates allowed patterns correctly`() {
		val registry = ServiceRegistry()

		assertTrue(registry.isAllowed("https://app.qoriginal.vip/login"))
		assertTrue(registry.isAllowed("https://app.qoriginal.vip/callback"))
		assertTrue(registry.isAllowed("https://qoriginal.vip/cas"))
		assertTrue(registry.isAllowed("https://ai.qoriginal.vip/auth/callback"))
		assertTrue(registry.isAllowed("https://kotshi.qoriginal.vip/callback"))
		assertTrue(registry.isAllowed("http://localhost:5173/callback"))
		assertTrue(registry.isAllowed("http://127.0.0.1:8080/"))

		assertFalse(registry.isAllowed("https://evil.com/callback"))
		assertFalse(registry.isAllowed("https://qoriginal.vip.attacker.com/"))
		assertFalse(registry.isAllowed("javascript:alert(1)"))
		assertFalse(registry.isAllowed(""))
		assertFalse(registry.isAllowed(null))
	}

	@Test
	fun `ticket store creates and consumes ticket once only`() {
		val store = SsoTicketStore()
		val record = store.createTicket("Glowingstone", "https://ai.qoriginal.vip/callback")

		assertTrue(record.ticket.startsWith("ST-"))
		assertEquals("Glowingstone", record.username)
		assertEquals("https://ai.qoriginal.vip/callback", record.service)

		// Cannot consume with mismatched service
		val mismatched = store.validateAndConsume(record.ticket, "https://other.service.com")
		assertNull(mismatched)

		// Can consume with matching service
		val consumed = store.validateAndConsume(record.ticket, "https://ai.qoriginal.vip/callback")
		assertNotNull(consumed)
		assertEquals("Glowingstone", consumed.username)

		// Second consumption attempt must fail (consumed / single use)
		val replayed = store.validateAndConsume(record.ticket, "https://ai.qoriginal.vip/callback")
		assertNull(replayed)
	}

	@Test
	fun `auth central controller grants ticket and validates correctly`() = runBlocking {
		val login = Mockito.mock(Login::class.java)
		val userDbRepository = Mockito.mock(UserDbRepository::class.java)
		val registry = ServiceRegistry()
		val store = SsoTicketStore()
		val service = AuthCentralService(login, registry, store, userDbRepository)
		val controller = AuthCentralController(service)

		val userToken = "valid-qhub-token"
		Mockito.`when`(login.validate(userToken)).thenReturn(Pair("Glowingstone", 0))
		Mockito.`when`(login.generateToken(64)).thenReturn("fresh-service-token-64")
		Mockito.`when`(userDbRepository.readAsync("Glowingstone")).thenReturn(
			Users(
				"Glowingstone",
				1294915648L,
				false,
				3,
				0,
				false,
				0,
				false,
				3,
				"encrypted_pass",
				"uuid_profile",
				0,
				10,
				0L,
				0L
			)
		)

		// 1. Unauthorized if no token
		val unauth = controller.grantTicket(null, null, TicketGrantBody("https://ai.qoriginal.vip/callback"))
		assertEquals(HttpStatus.UNAUTHORIZED, unauth.statusCode)
		assertTrue(unauth.body!!.contains("\"result\":false"))

		val missingService = controller.grantTicket("Bearer $userToken", null, TicketGrantBody())
		assertEquals(HttpStatus.BAD_REQUEST, missingService.statusCode)
		assertTrue(missingService.body!!.contains("\"message\":\"缺少 service 参数\""))
		val missingParameters = controller.serviceValidateGet(null, null)
		assertEquals(HttpStatus.BAD_REQUEST, missingParameters.statusCode)
		assertTrue(missingParameters.body!!.contains("\"success\":false"))
		assertTrue(missingParameters.body!!.contains("\"code\":\"BAD_REQUEST\""))

		// 2. Bad request if service is untrusted
		val untrusted = controller.grantTicket("Bearer $userToken", null, TicketGrantBody("https://phishing.com/callback"))
		assertEquals(HttpStatus.BAD_REQUEST, untrusted.statusCode)

		// 3. Grant valid ticket
		val grantResp = controller.grantTicket("Bearer $userToken", null, TicketGrantBody("https://ai.qoriginal.vip/callback"))
		assertEquals(HttpStatus.OK, grantResp.statusCode)
		assertTrue(grantResp.body!!.contains("\"result\":true"))
		assertTrue(grantResp.body!!.contains("\"ticket\":\"ST-"))
		assertTrue(grantResp.body!!.contains("https://ai.qoriginal.vip/callback?ticket=ST-"))

		// Extract ticket from response
		val ticketRegex = Regex(""""ticket":"(ST-[^"]+)"""")
		val match = ticketRegex.find(grantResp.body!!)
		assertNotNull(match)
		val ticket = match.groupValues[1]

		val invalidClient = controller.serviceValidatePost(
			ServiceValidateBody(ticket, "https://ai.qoriginal.vip/callback", "unknown-client"),
		)
		assertEquals(HttpStatus.BAD_REQUEST, invalidClient.statusCode)
		assertTrue(invalidClient.body!!.contains("\"code\":\"INVALID_CLIENT_ID\""))

		// 4. Validate ticket via POST
		val validateResp = controller.serviceValidatePost(
			ServiceValidateBody(ticket, "https://ai.qoriginal.vip/callback", "qmod"),
		)
		assertEquals(HttpStatus.OK, validateResp.statusCode)
		assertTrue(validateResp.body!!.contains("\"success\":true"))
		assertTrue(validateResp.body!!.contains("\"user\":\"Glowingstone\""))
		assertTrue(validateResp.body!!.contains("\"token\":\"fresh-service-token-64\""))
		assertTrue(validateResp.body!!.contains("\"clientId\":\"qmod\""))
		assertTrue(validateResp.body!!.contains("\"accountType\":\"qo\""))

		// 5. Re-validation must fail
		val replayedResp = controller.serviceValidatePost(ServiceValidateBody(ticket, "https://ai.qoriginal.vip/callback"))
		assertEquals(HttpStatus.UNAUTHORIZED, replayedResp.statusCode)
		assertTrue(replayedResp.body!!.contains("\"success\":false"))
		assertTrue(replayedResp.body!!.contains("INVALID_TICKET"))

		// 6. Test GET validation
		val grantResp2 = controller.grantTicket("Bearer $userToken", null, TicketGrantBody("https://kotshi.qoriginal.vip/callback"))
		val ticket2 = ticketRegex.find(grantResp2.body!!)!!.groupValues[1]
		val validateGetResp = controller.serviceValidateGet(ticket2, "https://kotshi.qoriginal.vip/callback")
		assertEquals(HttpStatus.OK, validateGetResp.statusCode)
		assertTrue(validateGetResp.body!!.contains("\"success\":true"))
		assertTrue(validateGetResp.body!!.contains("\"clientId\":\"game\""))
	}

	@Test
	fun `frozen account is rejected during serviceValidate`() = runBlocking {
		val login = Mockito.mock(Login::class.java)
		val userDbRepository = Mockito.mock(UserDbRepository::class.java)
		val registry = ServiceRegistry()
		val store = SsoTicketStore()
		val service = AuthCentralService(login, registry, store, userDbRepository)
		val controller = AuthCentralController(service)

		Mockito.`when`(login.validate("frozen-token")).thenReturn(Pair("FrozenUser", 0))
		Mockito.`when`(userDbRepository.readAsync("FrozenUser")).thenReturn(
			Users("FrozenUser", 9999L, true, 0, 0, false, 0, false, 0, "pw", "p", 0, 0, 0L, 0L)
		)

		val grantResp = controller.grantTicket("Bearer frozen-token", null, TicketGrantBody("https://ai.qoriginal.vip/callback"))
		val ticket = Regex(""""ticket":"(ST-[^"]+)"""").find(grantResp.body!!)!!.groupValues[1]

		val validateResp = controller.serviceValidatePost(ServiceValidateBody(ticket, "https://ai.qoriginal.vip/callback"))
		assertEquals(HttpStatus.FORBIDDEN, validateResp.statusCode)
		assertTrue(validateResp.body!!.contains("ACCOUNT_FROZEN"))
	}

	@Test
	fun `guest account via QQ login is supported in serviceValidate`() = runBlocking {
		val login = Mockito.mock(Login::class.java)
		val userDbRepository = Mockito.mock(UserDbRepository::class.java)
		val registry = ServiceRegistry()
		val store = SsoTicketStore()
		val service = AuthCentralService(login, registry, store, userDbRepository)
		val controller = AuthCentralController(service)

		val guestName = "qq:12345678"
		Mockito.`when`(login.validate("guest-token")).thenReturn(Pair(guestName, 0))
		Mockito.`when`(login.generateToken(64)).thenReturn("guest-client-token")
		Mockito.`when`(userDbRepository.readAsync(guestName)).thenReturn(null)

		val grantResp = controller.grantTicket("Bearer guest-token", null, TicketGrantBody("https://ai.qoriginal.vip/callback"))
		val ticket = Regex(""""ticket":"(ST-[^"]+)"""").find(grantResp.body!!)!!.groupValues[1]

		val validateResp = controller.serviceValidatePost(ServiceValidateBody(ticket, "https://ai.qoriginal.vip/callback"))
		assertEquals(HttpStatus.OK, validateResp.statusCode)
		assertTrue(validateResp.body!!.contains("\"accountType\":\"guest\""))
		assertTrue(validateResp.body!!.contains("\"uid\":12345678"))
		assertTrue(validateResp.body!!.contains("\"token\":\"guest-client-token\""))
	}

	@Test
	fun `session refresh extends the token while preserving its client ID`() = runBlocking {
		val login = Mockito.mock(Login::class.java)
		val userDbRepository = Mockito.mock(UserDbRepository::class.java)
		val service = AuthCentralService(login, ServiceRegistry(), SsoTicketStore(), userDbRepository)
		val controller = AuthCentralController(service)
		val token = "qmod-session-token"
		val expiresAt = System.currentTimeMillis() + 604_800_000L

		Mockito.`when`(login.validate(token)).thenReturn(Pair("Glowingstone", 0))
		Mockito.`when`(login.refreshToken(token)).thenReturn(
			LoginToken(token, "Glowingstone", expiresAt, "qmod"),
		)
		Mockito.`when`(userDbRepository.readAsync("Glowingstone")).thenReturn(
			Users(
				"Glowingstone",
				1294915648L,
				false,
				3,
				0,
				false,
				0,
				false,
				3,
				"encrypted_pass",
				"uuid_profile",
				0,
				10,
				0L,
				0L
			)
		)

		val response = controller.refreshSession("Bearer $token", null)
		assertEquals(HttpStatus.OK, response.statusCode)
		assertTrue(response.body!!.contains("\"clientId\":\"qmod\""))
		assertTrue(response.body!!.contains("\"token\":\"$token\""))
		assertTrue(response.body!!.contains("\"expiresAt\":$expiresAt"))
	}

	@Test
	fun `refresh rejects missing expired and concurrently revoked tokens`() = runBlocking {
		val login = Mockito.mock(Login::class.java)
		val users = Mockito.mock(UserDbRepository::class.java)
		val controller = AuthCentralController(AuthCentralService(login, ServiceRegistry(), SsoTicketStore(), users))
		assertEquals(HttpStatus.UNAUTHORIZED, controller.refreshSession(null, null).statusCode)
		Mockito.`when`(login.validate("expired")).thenReturn(null to 3)
		assertEquals(HttpStatus.UNAUTHORIZED, controller.refreshSession(null, "expired").statusCode)
		Mockito.verify(login, Mockito.never()).refreshToken("expired")

		Mockito.`when`(login.validate("revoked")).thenReturn("qq:12345678" to 0)
		Mockito.`when`(login.refreshToken("revoked")).thenReturn(null)
		assertEquals(HttpStatus.UNAUTHORIZED, controller.refreshSession(null, "revoked").statusCode)
	}

	@Test
	fun `refresh rejects and revokes tokens for deleted accounts`(): Unit = runBlocking {
		val login = Mockito.mock(Login::class.java)
		val users = Mockito.mock(UserDbRepository::class.java)
		val service = AuthCentralService(login, ServiceRegistry(), SsoTicketStore(), users)
		Mockito.`when`(login.validate("deleted")).thenReturn("DeletedUser" to 0)
		assertEquals(SessionRefreshResult.InvalidToken, service.refreshSession("deleted"))
		Mockito.verify(login).revokeToken("deleted")
		Mockito.verify(login, Mockito.never()).refreshToken("deleted")
	}

	@Test
	fun `refresh rejects frozen QO accounts including previously issued QQ guest tokens`() = runBlocking {
		val login = Mockito.mock(Login::class.java)
		val users = Mockito.mock(UserDbRepository::class.java)
		val service = AuthCentralService(login, ServiceRegistry(), SsoTicketStore(), users)
		val frozen = Users("FrozenUser", 12345678L, true, 0, 0, false, 0, false, 0, "pw", "p", 0, 0, 0L, 0L)
		Mockito.`when`(users.readAsync("FrozenUser")).thenReturn(frozen)
		Mockito.`when`(users.readAsync(12345678L)).thenReturn(frozen)
		for (name in listOf("FrozenUser", "qq:12345678")) {
			val token = "frozen-$name"
			Mockito.`when`(login.validate(token)).thenReturn(name to 0)
			assertEquals(SessionRefreshResult.AccountFrozen, service.refreshSession(token))
			Mockito.verify(login).revokeToken(token)
			Mockito.verify(login, Mockito.never()).refreshToken(token)
		}
	}

	@Test
	fun `refresh resolves QQ guest profiles to their current bound account`() = runBlocking {
		val login = Mockito.mock(Login::class.java)
		val users = Mockito.mock(UserDbRepository::class.java)
		val service = AuthCentralService(login, ServiceRegistry(), SsoTicketStore(), users)
		val expires = System.currentTimeMillis() + 604_800_000L
		Mockito.`when`(login.validate("guest")).thenReturn("qq:12345678" to 0)
		Mockito.`when`(login.refreshToken("guest")).thenReturn(LoginToken("guest", "qq:12345678", expires, "qcommunity"))
		val guest = service.refreshSession("guest") as SessionRefreshResult.Success
		assertEquals("guest", guest.accountType)
		assertEquals(12345678L, guest.uid)
		Mockito.`when`(users.readAsync(12345678L)).thenReturn(
			Users("BoundUser", 12345678L, false, 0, 0, false, 0, false, 0, "pw", "p", 0, 42, 0L, 0L),
		)
		val bound = service.refreshSession("guest") as SessionRefreshResult.Success
		assertEquals("qo", bound.accountType)
		assertEquals("BoundUser", bound.user)
		assertEquals(42, bound.score)
		assertEquals("qcommunity", bound.clientId)
	}
}
