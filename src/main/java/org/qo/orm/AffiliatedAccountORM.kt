package org.qo.orm

import org.qo.datas.ConnectionPool
import org.qo.services.loginService.AffiliatedAccountServices
import org.springframework.stereotype.Service

@Service
class AffiliatedAccountORM : CrudDao<AffiliatedAccountServices.AffiliatedAccount> {
	fun createUsingInvite(item: AffiliatedAccountServices.AffiliatedAccount): Boolean {
		ConnectionPool.getConnection().use { connection ->
			connection.autoCommit = false
			try {
				val inviteConsumed = connection.prepareStatement(
					"UPDATE users SET invite = invite - 1 WHERE username = ? AND invite > 0"
				).use { statement ->
					statement.setString(1, item.host)
					statement.executeUpdate() == 1
				}
				if (!inviteConsumed) {
					connection.rollback()
					return false
				}

				val accountCreated = connection.prepareStatement(
					"INSERT INTO affiliated_account (name, host, password) VALUES (?, ?, ?)"
				).use { statement ->
					statement.setString(1, item.name)
					statement.setString(2, item.host)
					statement.setString(3, item.password)
					statement.executeUpdate() == 1
				}
				if (!accountCreated) {
					connection.rollback()
					return false
				}

				connection.commit()
				return true
			} catch (exception: Exception) {
				connection.rollback()
				throw exception
			}
		}
	}

	override fun create(item: AffiliatedAccountServices.AffiliatedAccount): Long {
		ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement("INSERT INTO affiliated_account (name, host, password) VALUES (?, ?, ?)").use {
				it.setString(1, item.name)
				it.setString(2, item.host)
				it.setString(3, item.password)
				return it.executeUpdate().toLong()
			}
		}
	}

	override fun read(input: Any): AffiliatedAccountServices.AffiliatedAccount? {
		ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement("SELECT * FROM affiliated_account WHERE name = ?").use {
				it.setString(1, input as String)
				val rs = it.executeQuery()
				return if (rs.next()) {
					AffiliatedAccountServices.AffiliatedAccount(
						rs.getString("name"),
						rs.getString("host"),
						rs.getString("password")
					)
				} else {
					null
				}
			}
		}
	}

	fun readByHost(host: String): List<AffiliatedAccountServices.AffiliatedAccount> {
		ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement("SELECT * FROM affiliated_account WHERE host = ?").use {
				it.setString(1, host)
				val rs = it.executeQuery()
				val result = mutableListOf<AffiliatedAccountServices.AffiliatedAccount>()
				while (rs.next()) {
					result.add(
						AffiliatedAccountServices.AffiliatedAccount(
							rs.getString("name"),
							rs.getString("host"),
							rs.getString("password")
						)
					)
				}
				return result
			}
		}
	}

	override fun update(item: AffiliatedAccountServices.AffiliatedAccount): Boolean {
		throw UnsupportedOperationException("Affiliated accounts cannot be modified")
	}

	override fun delete(input: Any): Boolean {
		throw UnsupportedOperationException("Affiliated account deletion requires its host")
	}

	fun deleteByNameAndHost(name: String, host: String): Boolean {
		ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement("DELETE FROM affiliated_account WHERE name = ? AND host = ?").use {
				it.setString(1, name)
				it.setString(2, host)
				return it.executeUpdate() > 0
			}
		}
	}

}
