package org.qo.services.loginService

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.qo.TestApiApplication
import org.qo.datas.Nodes
import org.qo.utils.ReturnInterface
import org.qo.services.llmServices.KotshiAccountService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(
	classes = [TestApiApplication::class, AuthorityNeededServicesController::class, ReturnInterface::class],
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureWebTestClient
class AuthorityNeededServicesControllerTest {
	@Autowired
	lateinit var webTestClient: WebTestClient

	@MockitoBean
	lateinit var login: Login

	@MockitoBean
	lateinit var ipWhitelistServices: IPWhitelistServices

	@MockitoBean
	lateinit var authorityNeededServices: AuthorityNeededServicesImpl

	@MockitoBean
	lateinit var playerCardCustomization: PlayerCardCustomizationImpl

	@MockitoBean
	lateinit var affiliatedAccountServices: AffiliatedAccountServices

	@MockitoBean
	lateinit var nodes: Nodes

	@MockitoBean
	lateinit var recentLoginService: RecentLoginService

	@MockitoBean
	lateinit var kotshiAccountService: KotshiAccountService

	@Test
	fun autoLogin_allowsRecentLoginFromSameIpForAuthenticatedServer() {
		Mockito.`when`(nodes.getServerFromToken("server-token")).thenReturn(1)
		Mockito.`when`(recentLoginService.canAutoLogin("alex", "203.0.113.8")).thenReturn(true)

		webTestClient.post()
			.uri("/qo/authorization/auto-login")
			.header("Token", "server-token")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""{"username":"alex","ip":"203.0.113.8"}""")
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.ok").isEqualTo(true)

		Mockito.verify(recentLoginService).canAutoLogin("alex", "203.0.113.8")
	}

	@Test
	fun autoLogin_rejectsUnauthenticatedServer() {
		Mockito.`when`(nodes.getServerFromToken("invalid-token")).thenReturn(-1)

		webTestClient.post()
			.uri("/qo/authorization/auto-login")
			.header("Token", "invalid-token")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""{"username":"alex","ip":"203.0.113.8"}""")
			.exchange()
			.expectStatus().isUnauthorized
			.expectBody()
			.jsonPath("$.ok").isEqualTo(false)

		Mockito.verifyNoInteractions(recentLoginService)
	}

	@Test
	fun removeIp_deletesAuthenticatedUsersWhitelistEntry() = runBlocking {
		Mockito.`when`(login.validate("login-token")).thenReturn(Pair("alex", 0))
		Mockito.`when`(authorityNeededServices.doPrecheck("alex", 0)).thenReturn(null)
		Mockito.`when`(ipWhitelistServices.leaveWhitelist("1.2.3.4", "login-token"))
			.thenReturn(IPWhitelistServices.WhitelistReasons.SUCCESS)

		webTestClient.delete()
			.uri("/qo/authorization/ip/remove?ip=1.2.3.4")
			.header("token", "login-token")
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.code").isEqualTo(0)

		Mockito.verify(ipWhitelistServices).leaveWhitelist("1.2.3.4", "login-token")
	}

	@Test
	fun removeAffiliatedAccount_deletesAuthenticatedHostsAccount() = runBlocking {
		Mockito.`when`(affiliatedAccountServices.removeAffiliatedAccount("login-token", "child"))
			.thenReturn(true)

		webTestClient.delete()
			.uri("/qo/authorization/affiliated/remove?name=child")
			.header("token", "login-token")
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.result").isEqualTo(true)

		Mockito.verify(affiliatedAccountServices).removeAffiliatedAccount("login-token", "child")
	}

	@Test
	fun addIp_returnsInvalidIpCode() = runBlocking {
		Mockito.`when`(login.validate("login-token")).thenReturn(Pair("alex", 0))
		Mockito.`when`(authorityNeededServices.doPrecheck("alex", 0)).thenReturn(null)
		Mockito.`when`(ipWhitelistServices.joinWhitelist("example.com", "login-token"))
			.thenReturn(IPWhitelistServices.WhitelistReasons.INVALID_IP)

		webTestClient.get()
			.uri("/qo/authorization/ip/add?ip=example.com")
			.header("token", "login-token")
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.code").isEqualTo(4)
	}
}
