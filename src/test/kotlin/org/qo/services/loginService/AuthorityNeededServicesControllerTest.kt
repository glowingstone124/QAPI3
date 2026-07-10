package org.qo.services.loginService

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.qo.TestApiApplication
import org.qo.utils.ReturnInterface
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
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
