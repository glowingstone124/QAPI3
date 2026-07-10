package org.qo.services.loginService

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.qo.orm.AffiliatedAccountORM

class AffiliatedAccountServicesTest {
	private val orm = Mockito.mock(AffiliatedAccountORM::class.java)
	private val login = Mockito.mock(Login::class.java)
	private val service = AffiliatedAccountServices(orm, login)

	@Test
	fun removeAffiliatedAccount_deletesOnlyForAuthenticatedHost() = runBlocking {
		Mockito.`when`(login.validate("host-token")).thenReturn(Pair("host", 0))
		Mockito.`when`(orm.deleteByNameAndHost("child", "host")).thenReturn(true)

		val result = service.removeAffiliatedAccount("host-token", "child")

		assertTrue(result)
		Mockito.verify(orm).deleteByNameAndHost("child", "host")
	}

	@Test
	fun removeAffiliatedAccount_rejectsInvalidTokenWithoutDeleting() = runBlocking {
		Mockito.`when`(login.validate("bad-token")).thenReturn(Pair(null, 1))

		val result = service.removeAffiliatedAccount("bad-token", "child")

		assertFalse(result)
		Mockito.verify(orm, Mockito.never()).deleteByNameAndHost(Mockito.anyString(), Mockito.anyString())
	}

	@Test
	fun getAffiliatedAccount_doesNotExposePasswordHash() = runBlocking {
		Mockito.`when`(login.validate("host-token")).thenReturn(Pair("host", 0))
		Mockito.`when`(orm.readByHost("host")).thenReturn(
			listOf(AffiliatedAccountServices.AffiliatedAccount("child", "host", "password-hash"))
		)

		val result = service.getAffiliatedAccount("host-token")

		assertEquals(
			listOf(AffiliatedAccountServices.AffiliatedAccountSummary("child", "host")),
			result
		)
	}
}
