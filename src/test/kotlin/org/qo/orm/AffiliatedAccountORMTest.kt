package org.qo.orm

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.qo.TestApiApplication
import org.qo.datas.R2dbcDatabaseConfiguration
import org.qo.datas.ReactiveDatabase
import org.qo.services.loginService.AffiliatedAccountServices
import org.qo.utils.SpringContextUtil
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
	classes = [
		TestApiApplication::class,
		ReactiveDatabase::class,
		R2dbcDatabaseConfiguration::class,
		SpringContextUtil::class,
		AffiliatedAccountORM::class,
	],
)
class AffiliatedAccountORMTest {
	@Autowired
	lateinit var database: ReactiveDatabase

	@Autowired
	lateinit var orm: AffiliatedAccountORM

	@BeforeEach
	fun setUp() {
		runBlocking {
		database.execute("DROP TABLE IF EXISTS affiliated_account")
		database.execute(
			"CREATE TABLE affiliated_account (name VARCHAR(255) PRIMARY KEY, host VARCHAR(255) NOT NULL, password VARCHAR(255) NOT NULL)"
		)
		}
	}

	@Test
	fun deleteByNameAndHost_doesNotDeleteAnotherHostsAccount() = runBlocking {
		orm.createAsync(AffiliatedAccountServices.AffiliatedAccount("child", "host-a", "hash"))

		assertFalse(orm.deleteByNameAndHostAsync("child", "host-b"))
		assertTrue(orm.readAsync("child") != null)
	}

	@Test
	fun deleteByNameAndHost_deletesOwnedAccount() = runBlocking {
		orm.createAsync(AffiliatedAccountServices.AffiliatedAccount("child", "host-a", "hash"))

		assertTrue(orm.deleteByNameAndHostAsync("child", "host-a"))
		assertTrue(orm.readAsync("child") == null)
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
