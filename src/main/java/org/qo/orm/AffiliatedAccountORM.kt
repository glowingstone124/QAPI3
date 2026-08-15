package org.qo.orm

import org.qo.datas.ReactiveDatabase
import org.qo.services.loginService.AffiliatedAccountServices
import org.springframework.stereotype.Service

@Service
class AffiliatedAccountORM : CrudDao<AffiliatedAccountServices.AffiliatedAccount> {
	private var databaseOverride: ReactiveDatabase? = null

	constructor()

	constructor(database: ReactiveDatabase) : this() {
		this.databaseOverride = database
	}

	private val database: ReactiveDatabase
		get() = reactiveDatabase(databaseOverride)

	private class RollbackSignal : RuntimeException()

	suspend fun createUsingInviteAsync(item: AffiliatedAccountServices.AffiliatedAccount): Boolean = try {
		database.inTransaction {
			val inviteConsumed = database.execute(
				"UPDATE users SET invite = invite - 1 WHERE username = ? AND invite > 0",
				listOf(item.host),
			) == 1L
			if (!inviteConsumed) {
				throw RollbackSignal()
			}

			val accountCreated = database.execute(
				"INSERT INTO affiliated_account (name, host, password) VALUES (?, ?, ?)",
				listOf(item.name, item.host, item.password),
			) == 1L
			if (!accountCreated) {
				throw RollbackSignal()
			}

			true
		}
	} catch (_: RollbackSignal) {
		false
	}

	override fun create(item: AffiliatedAccountServices.AffiliatedAccount): Long =
		unsupportedSyncApi("AffiliatedAccountORM.create")

	suspend fun createAsync(item: AffiliatedAccountServices.AffiliatedAccount): Long =
		if (
			database.execute(
				"INSERT INTO affiliated_account (name, host, password) VALUES (?, ?, ?)",
				listOf(item.name, item.host, item.password),
			) == 1L
		) 1L else 0L

	override fun read(input: Any): AffiliatedAccountServices.AffiliatedAccount? =
		unsupportedSyncApi("AffiliatedAccountORM.read")

	suspend fun readAsync(name: String): AffiliatedAccountServices.AffiliatedAccount? = database.one(
		"SELECT name, host, password FROM affiliated_account WHERE name = ?",
		listOf(name),
		::mapRow,
	)

	suspend fun readByHostAsync(host: String): List<AffiliatedAccountServices.AffiliatedAccount> = database.all(
		"SELECT name, host, password FROM affiliated_account WHERE host = ?",
		listOf(host),
		::mapRow,
	)

	override fun update(item: AffiliatedAccountServices.AffiliatedAccount): Boolean {
		throw UnsupportedOperationException("Affiliated accounts cannot be modified")
	}

	override fun delete(input: Any): Boolean {
		throw UnsupportedOperationException("Affiliated account deletion requires its host")
	}

	suspend fun deleteByNameAndHostAsync(name: String, host: String): Boolean =
		database.execute(
			"DELETE FROM affiliated_account WHERE name = ? AND host = ?",
			listOf(name, host),
		) > 0

	fun deleteByNameAndHost(name: String, host: String): Boolean =
		unsupportedSyncApi("AffiliatedAccountORM.deleteByNameAndHost")

	private fun mapRow(row: io.r2dbc.spi.Row): AffiliatedAccountServices.AffiliatedAccount =
		AffiliatedAccountServices.AffiliatedAccount(
			name = row.get("name", String::class.java).orEmpty(),
			host = row.get("host", String::class.java).orEmpty(),
			password = row.get("password", String::class.java).orEmpty(),
		)
}
