package org.qo.orm

import org.qo.datas.ConnectionPool
import org.qo.services.loginService.AffiliatedAccountServices
import org.springframework.stereotype.Service

@Service
class AffiliatedAccountORM : CrudDao<AffiliatedAccountServices.AffiliatedAccount> {
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
		ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement("UPDATE affiliated_account SET name = ?, host = ?, password = ? WHERE name = ?").use {
				it.setString(1, item.name)
				it.setString(2, item.host)
				it.setString(3, item.password)
				it.setString(4, item.name)
				return it.executeUpdate() > 0
			}
		}
	}

	override fun delete(input: Any): Boolean {
		ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement("DELETE FROM affiliated_account WHERE name = ?").use {
				it.setString(1, input as String)
				return it.executeUpdate() > 0
			}
		}
	}

}