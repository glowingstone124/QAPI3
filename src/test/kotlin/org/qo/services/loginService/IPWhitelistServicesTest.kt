package org.qo.services.loginService

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.qo.TestApiApplication
import org.qo.datas.R2dbcDatabaseConfiguration
import org.qo.datas.ReactiveDatabase
import org.qo.utils.SpringContextUtil
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@SpringBootTest(
	classes = [
		TestApiApplication::class,
		ReactiveDatabase::class,
		R2dbcDatabaseConfiguration::class,
		SpringContextUtil::class,
		IPWhitelistServices::class,
	],
)
class IPWhitelistServicesTest {
	@Autowired
	lateinit var database: ReactiveDatabase

	@Autowired
	lateinit var service: IPWhitelistServices

	@MockitoBean
	lateinit var login: Login

	@MockitoBean
	lateinit var authorityNeededServices: AuthorityNeededServicesImpl

	@BeforeEach
	fun setUp() {
		runBlocking {
		database.execute("DROP TABLE IF EXISTS loginip")
		database.execute("DROP TABLE IF EXISTS users")
		database.execute(
			"CREATE TABLE users (username VARCHAR(255) PRIMARY KEY, invite INT NOT NULL DEFAULT 0)"
		)
		database.execute(
			"CREATE TABLE loginip (username VARCHAR(255) NOT NULL, ip VARCHAR(45) NOT NULL)"
		)
		database.execute("INSERT INTO users (username, invite) VALUES (?, ?)", listOf("alex", 0))
		database.execute("INSERT INTO users (username, invite) VALUES (?, ?)", listOf("steve", 0))
		}
	}

	@Test
	fun removeFromWhitelist_deletesExistingEntry_returnsTrue() = runBlocking {
		service.addIntoWhitelistAsync("1.2.3.4", "alex")

		val result = service.removeFromWhitelistAsync("1.2.3.4", "alex")

		assertTrue(result)
		assertFalse(service.whitelistedAsync("1.2.3.4"))
		assertEquals(0, service.whitelistedIpCountAsync("alex"))
	}

	@Test
	fun removeFromWhitelist_nonExistentEntry_returnsFalse() = runBlocking {
		val result = service.removeFromWhitelistAsync("9.9.9.9", "alex")

		assertFalse(result)
	}

	@Test
	fun removeFromWhitelist_onlyRemovesMatchingUsername() = runBlocking {
		service.addIntoWhitelistAsync("1.2.3.4", "alex")
		service.addIntoWhitelistAsync("1.2.3.4", "steve")

		val result = service.removeFromWhitelistAsync("1.2.3.4", "alex")

		assertTrue(result)
		assertEquals(0, service.whitelistedIpCountAsync("alex"))
		assertEquals(1, service.whitelistedIpCountAsync("steve"))
		assertTrue(service.whitelistedAsync("1.2.3.4"))
	}

	@Test
	fun leaveWhitelist_removesOnlyTheAuthenticatedUsersEntry() = runBlocking {
		service.addIntoWhitelistAsync("1.2.3.4", "alex")
		service.addIntoWhitelistAsync("1.2.3.4", "steve")
		Mockito.`when`(login.validate("alex-token")).thenReturn(Pair("alex", 0))
		Mockito.`when`(authorityNeededServices.doPrecheck("alex", 0)).thenReturn(null)

		val result = service.leaveWhitelist("1.2.3.4", "alex-token")

		assertEquals(IPWhitelistServices.WhitelistReasons.SUCCESS, result)
		assertEquals(0, service.whitelistedIpCountAsync("alex"))
		assertEquals(1, service.whitelistedIpCountAsync("steve"))
	}

	@Test
	fun leaveWhitelist_rejectsInvalidToken() = runBlocking {
		service.addIntoWhitelistAsync("1.2.3.4", "alex")
		Mockito.`when`(login.validate("bad-token")).thenReturn(Pair(null, 1))
		Mockito.`when`(authorityNeededServices.doPrecheck(null, 1)).thenReturn("invalid")

		val result = service.leaveWhitelist("1.2.3.4", "bad-token")

		assertEquals(IPWhitelistServices.WhitelistReasons.TOKEN_INVALID, result)
		assertEquals(1, service.whitelistedIpCountAsync("alex"))
	}

	@Test
	fun joinWhitelist_isIdempotentForTheSameUserAndIp() = runBlocking {
		service.addIntoWhitelistAsync("1.2.3.4", "alex")
		Mockito.`when`(login.validate("alex-token")).thenReturn(Pair("alex", 0))
		Mockito.`when`(authorityNeededServices.doPrecheck("alex", 0)).thenReturn(null)

		val result = service.joinWhitelist("1.2.3.4", "alex-token")

		assertEquals(IPWhitelistServices.WhitelistReasons.SUCCESS, result)
		assertEquals(1, service.whitelistedIpCountAsync("alex"))
	}

	@Test
	fun normalizeIp_acceptsStrictIpv4AndIpv6Literals() {
		assertEquals("1.2.3.4", service.normalizeIp("1.2.3.4"))
		assertEquals("2001:db8:0:0:0:0:0:1", service.normalizeIp("2001:db8::1"))
	}

	@Test
	fun normalizeIp_rejectsInvalidOrAmbiguousInputs() {
		listOf(
			"example.com",
			" 1.2.3.4",
			"1.2.3.4 ",
			"1.2.3",
			"1.2.3.256",
			"1.2.03.4",
			"2001:db8::1%eth0",
			"",
		).forEach { assertEquals(null, service.normalizeIp(it), it) }
	}

	@Test
	fun joinWhitelist_rejectsInvalidIpWithoutWriting() = runBlocking {
		val result = service.joinWhitelist("example.com", "alex-token")

		assertEquals(IPWhitelistServices.WhitelistReasons.INVALID_IP, result)
		assertEquals(0, service.whitelistedIpCountAsync("alex"))
		Mockito.verifyNoInteractions(login, authorityNeededServices)
	}

	@Test
	fun joinWhitelist_storesNormalizedIpv6() = runBlocking {
		Mockito.`when`(login.validate("alex-token")).thenReturn(Pair("alex", 0))
		Mockito.`when`(authorityNeededServices.doPrecheck("alex", 0)).thenReturn(null)

		val result = service.joinWhitelist("2001:db8::1", "alex-token")

		assertEquals(IPWhitelistServices.WhitelistReasons.SUCCESS, result)
		assertTrue(service.whitelistedAsync("2001:db8::1", "alex"))
	}

	@Test
	fun joinWhitelist_rejectsSixthIpWithinTransaction() = runBlocking {
		Mockito.`when`(login.validate("alex-token")).thenReturn(Pair("alex", 0))
		Mockito.`when`(authorityNeededServices.doPrecheck("alex", 0)).thenReturn(null)
		repeat(5) { index ->
			assertEquals(
				IPWhitelistServices.WhitelistReasons.SUCCESS,
				service.joinWhitelist("198.51.100.${index + 1}", "alex-token"),
			)
		}

		val result = service.joinWhitelist("198.51.100.6", "alex-token")

		assertEquals(IPWhitelistServices.WhitelistReasons.IP_WHITELIST_FULL, result)
		assertEquals(5, service.whitelistedIpCountAsync("alex"))
	}

	@Test
	fun concurrentJoins_neverExceedFiveIps() {
		runBlocking {
			Mockito.`when`(login.validate("alex-token")).thenReturn(Pair("alex", 0))
			Mockito.`when`(authorityNeededServices.doPrecheck("alex", 0)).thenReturn(null)
		}
		val executor = Executors.newFixedThreadPool(10)
		try {
			val results = executor.invokeAll(
				(1..10).map { index ->
					Callable {
						runBlocking { service.joinWhitelist("203.0.113.$index", "alex-token") }
					}
				},
			).map { it.get() }

			assertEquals(5, results.count { it == IPWhitelistServices.WhitelistReasons.SUCCESS })
			assertEquals(5, results.count { it == IPWhitelistServices.WhitelistReasons.IP_WHITELIST_FULL })
			runBlocking {
				assertEquals(5, service.whitelistedIpCountAsync("alex"))
			}
		} finally {
			executor.shutdownNow()
		}
	}
}
