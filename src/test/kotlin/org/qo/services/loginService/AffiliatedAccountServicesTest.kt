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
	fun addAffiliatedAccount_createsAccountAndConsumesInviteAtomically() = runBlocking {
		val successfulOrm = Mockito.mock(AffiliatedAccountORM::class.java) { invocation ->
			if (invocation.method.name == "createUsingInviteAsync") true
			else Mockito.RETURNS_DEFAULTS.answer(invocation)
		}
		val successfulService = AffiliatedAccountServices(successfulOrm, login)
		Mockito.`when`(login.validate("host-token")).thenReturn(Pair("host", 0))

		val result = successfulService.addAffiliatedAccount(
			"host-token",
			"""{"name":"child","password":"secret"}"""
		)

		assertTrue(result)
		val invocation = Mockito.mockingDetails(successfulOrm).invocations.single {
			it.method.name == "createUsingInviteAsync"
		}
		val account = invocation.arguments.single() as AffiliatedAccountServices.AffiliatedAccount
		assertEquals("child", account.name)
		assertEquals("host", account.host)
		assertTrue(account.password != "secret")
	}

	@Test
	fun addAffiliatedAccount_returnsFalseWhenNoInviteCanBeConsumed() = runBlocking {
		val unsuccessfulOrm = Mockito.mock(AffiliatedAccountORM::class.java) { invocation ->
			if (invocation.method.name == "createUsingInviteAsync") false
			else Mockito.RETURNS_DEFAULTS.answer(invocation)
		}
		val unsuccessfulService = AffiliatedAccountServices(unsuccessfulOrm, login)
		Mockito.`when`(login.validate("host-token")).thenReturn(Pair("host", 0))

		val result = unsuccessfulService.addAffiliatedAccount(
			"host-token",
			"""{"name":"child","password":"secret"}"""
		)

		assertFalse(result)
	}

	@Test
	fun removeAffiliatedAccount_deletesOnlyForAuthenticatedHost() = runBlocking {
		Mockito.`when`(login.validate("host-token")).thenReturn(Pair("host", 0))
		Mockito.`when`(orm.deleteByNameAndHostAsync("child", "host")).thenReturn(true)

		val result = service.removeAffiliatedAccount("host-token", "child")

		assertTrue(result)
		Mockito.verify(orm).deleteByNameAndHostAsync("child", "host")
	}

	@Test
	fun removeAffiliatedAccount_rejectsInvalidTokenWithoutDeleting() = runBlocking {
		Mockito.`when`(login.validate("bad-token")).thenReturn(Pair(null, 1))

		val result = service.removeAffiliatedAccount("bad-token", "child")

		assertFalse(result)
		Mockito.verify(orm, Mockito.never()).deleteByNameAndHostAsync(Mockito.anyString(), Mockito.anyString())
	}

	@Test
	fun getAffiliatedAccount_doesNotExposePasswordHash() = runBlocking {
		Mockito.`when`(login.validate("host-token")).thenReturn(Pair("host", 0))
		Mockito.`when`(orm.readByHostAsync("host")).thenReturn(
			listOf(AffiliatedAccountServices.AffiliatedAccount("child", "host", "password-hash"))
		)

		val result = service.getAffiliatedAccount("host-token")

		assertEquals(
			listOf(AffiliatedAccountServices.AffiliatedAccountSummary("child", "host")),
			result
		)
	}
}
