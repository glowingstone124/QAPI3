package org.qo.services.loginService

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.qo.datas.ConnectionPool
import org.sqlite.SQLiteDataSource
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class IPWhitelistServicesTest {
	@TempDir
	lateinit var tempDir: Path

	private val login = Mockito.mock(Login::class.java)
	private val authorityNeededServices = Mockito.mock(AuthorityNeededServicesImpl::class.java)
	private val service = IPWhitelistServices(
		login,
		authorityNeededServices
	)

	@BeforeEach
	fun setUp() {
		ConnectionPool.ds = SQLiteDataSource().apply {
			url = "jdbc:sqlite:${tempDir.resolve("ipwhitelist.db")}"
		}
		ConnectionPool.getConnection().use { conn ->
			conn.createStatement().use { stmt ->
				stmt.executeUpdate(
					"""
					CREATE TABLE loginip (
						username TEXT NOT NULL,
						ip TEXT NOT NULL
					)
					""".trimIndent()
				)
			}
		}
	}

	@AfterEach
	fun tearDown() {
		ConnectionPool.ds = null
	}

	@Test
	fun removeFromWhitelist_deletesExistingEntry_returnsTrue() {
		service.addIntoWhitelist("1.2.3.4", "alex")

		val result = service.removeFromWhitelist("1.2.3.4", "alex")

		assertTrue(result)
		assertFalse(service.whitelisted("1.2.3.4"))
		assertEquals(0, service.whitelistedIpCount("alex"))
	}

	@Test
	fun removeFromWhitelist_nonExistentEntry_returnsFalse() {
		val result = service.removeFromWhitelist("9.9.9.9", "alex")

		assertFalse(result)
	}

	@Test
	fun removeFromWhitelist_onlyRemovesMatchingUsername() {
		service.addIntoWhitelist("1.2.3.4", "alex")
		service.addIntoWhitelist("1.2.3.4", "steve")

		val result = service.removeFromWhitelist("1.2.3.4", "alex")

		assertTrue(result)
		assertEquals(0, service.whitelistedIpCount("alex"))
		assertEquals(1, service.whitelistedIpCount("steve"))
		assertTrue(service.whitelisted("1.2.3.4"))
	}

	@Test
	fun leaveWhitelist_removesOnlyTheAuthenticatedUsersEntry() = runBlocking {
		service.addIntoWhitelist("1.2.3.4", "alex")
		service.addIntoWhitelist("1.2.3.4", "steve")
		Mockito.`when`(login.validate("alex-token")).thenReturn(Pair("alex", 0))
		Mockito.`when`(authorityNeededServices.doPrecheck("alex", 0)).thenReturn(null)

		val result = service.leaveWhitelist("1.2.3.4", "alex-token")

		assertEquals(IPWhitelistServices.WhitelistReasons.SUCCESS, result)
		assertEquals(0, service.whitelistedIpCount("alex"))
		assertEquals(1, service.whitelistedIpCount("steve"))
	}

	@Test
	fun leaveWhitelist_rejectsInvalidToken() = runBlocking {
		service.addIntoWhitelist("1.2.3.4", "alex")
		Mockito.`when`(login.validate("bad-token")).thenReturn(Pair(null, 1))
		Mockito.`when`(authorityNeededServices.doPrecheck(null, 1)).thenReturn("invalid")

		val result = service.leaveWhitelist("1.2.3.4", "bad-token")

		assertEquals(IPWhitelistServices.WhitelistReasons.TOKEN_INVALID, result)
		assertEquals(1, service.whitelistedIpCount("alex"))
	}

	@Test
	fun joinWhitelist_isIdempotentForTheSameUserAndIp() = runBlocking {
		service.addIntoWhitelist("1.2.3.4", "alex")
		Mockito.`when`(login.validate("alex-token")).thenReturn(Pair("alex", 0))
		Mockito.`when`(authorityNeededServices.doPrecheck("alex", 0)).thenReturn(null)

		val result = service.joinWhitelist("1.2.3.4", "alex-token")

		assertEquals(IPWhitelistServices.WhitelistReasons.SUCCESS, result)
		assertEquals(1, service.whitelistedIpCount("alex"))
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
			""
		).forEach { assertEquals(null, service.normalizeIp(it), it) }
	}

	@Test
	fun joinWhitelist_rejectsInvalidIpWithoutWriting() = runBlocking {
		val result = service.joinWhitelist("example.com", "alex-token")

		assertEquals(IPWhitelistServices.WhitelistReasons.INVALID_IP, result)
		assertEquals(0, service.whitelistedIpCount("alex"))
		Mockito.verifyNoInteractions(login, authorityNeededServices)
	}

	@Test
	fun joinWhitelist_storesNormalizedIpv6() = runBlocking {
		Mockito.`when`(login.validate("alex-token")).thenReturn(Pair("alex", 0))
		Mockito.`when`(authorityNeededServices.doPrecheck("alex", 0)).thenReturn(null)

		val result = service.joinWhitelist("2001:db8::1", "alex-token")

		assertEquals(IPWhitelistServices.WhitelistReasons.SUCCESS, result)
		assertTrue(service.whitelisted("2001:db8::1", "alex"))
	}

	@Test
	fun joinWhitelist_rejectsSixthIpWithinTransaction() = runBlocking {
		Mockito.`when`(login.validate("alex-token")).thenReturn(Pair("alex", 0))
		Mockito.`when`(authorityNeededServices.doPrecheck("alex", 0)).thenReturn(null)
		repeat(5) { index ->
			assertEquals(
				IPWhitelistServices.WhitelistReasons.SUCCESS,
				service.joinWhitelist("198.51.100.${index + 1}", "alex-token")
			)
		}

		val result = service.joinWhitelist("198.51.100.6", "alex-token")

		assertEquals(IPWhitelistServices.WhitelistReasons.IP_WHITELIST_FULL, result)
		assertEquals(5, service.whitelistedIpCount("alex"))
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
				}
			).map { it.get() }

			assertEquals(5, results.count { it == IPWhitelistServices.WhitelistReasons.SUCCESS })
			assertEquals(5, results.count { it == IPWhitelistServices.WhitelistReasons.IP_WHITELIST_FULL })
			assertEquals(5, service.whitelistedIpCount("alex"))
		} finally {
			executor.shutdownNow()
		}
	}
}
