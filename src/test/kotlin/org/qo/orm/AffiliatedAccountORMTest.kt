package org.qo.orm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.qo.datas.ConnectionPool
import org.qo.services.loginService.AffiliatedAccountServices
import org.sqlite.SQLiteDataSource
import java.nio.file.Path

class AffiliatedAccountORMTest {
	@TempDir
	lateinit var tempDir: Path

	private val orm = AffiliatedAccountORM()

	@BeforeEach
	fun setUp() {
		ConnectionPool.ds = SQLiteDataSource().apply {
			url = "jdbc:sqlite:${tempDir.resolve("affiliated-account.db")}"
		}
		ConnectionPool.getConnection().use { connection ->
			connection.createStatement().use {
				it.executeUpdate(
					"CREATE TABLE affiliated_account (name TEXT PRIMARY KEY, host TEXT NOT NULL, password TEXT NOT NULL)"
				)
			}
		}
	}

	@AfterEach
	fun tearDown() {
		ConnectionPool.ds = null
	}

	@Test
	fun deleteByNameAndHost_doesNotDeleteAnotherHostsAccount() {
		orm.create(AffiliatedAccountServices.AffiliatedAccount("child", "host-a", "hash"))

		assertFalse(orm.deleteByNameAndHost("child", "host-b"))
		assertTrue(orm.read("child") != null)
	}

	@Test
	fun deleteByNameAndHost_deletesOwnedAccount() {
		orm.create(AffiliatedAccountServices.AffiliatedAccount("child", "host-a", "hash"))

		assertTrue(orm.deleteByNameAndHost("child", "host-a"))
		assertTrue(orm.read("child") == null)
	}

	@Test
	fun update_isNotSupported() {
		val account = AffiliatedAccountServices.AffiliatedAccount("child", "host-a", "hash")

		assertThrows(UnsupportedOperationException::class.java) {
			orm.update(account)
		}
	}

	@Test
	fun deleteWithoutHost_isNotSupported() {
		assertThrows(UnsupportedOperationException::class.java) {
			orm.delete("child")
		}
	}
}
