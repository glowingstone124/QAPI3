package org.qo.orm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.qo.datas.ConnectionPool
import java.sql.ResultSet

data class LoginToken(
	val token: String,
	val user: String,
	val expires: Long
)

class LoginTokenORM {

	suspend fun create(item: LoginToken) {
		val sql = "INSERT INTO login_tokens (token, user, expires) VALUES (?, ?, ?)"
		withContext(Dispatchers.IO) {
			ConnectionPool.getConnection().use { connection ->
				connection.prepareStatement(sql).use { stmt ->
					stmt.setString(1, item.token)
					stmt.setString(2, item.user)
					stmt.setLong(3, item.expires)
					stmt.executeUpdate()
				}
			}
		}
	}

	suspend fun read(token: String): LoginToken? {
		val sql = "SELECT * FROM login_tokens WHERE token = ?"
		return withContext(Dispatchers.IO) {
			ConnectionPool.getConnection().use { connection ->
				connection.prepareStatement(sql).use { stmt ->
					stmt.setString(1, token)
					val result = stmt.executeQuery()
					if (result.next()) mapRowToLoginToken(result) else null
				}
			}
		}
	}


	suspend fun delete(token: String): Boolean {
		val sql = "DELETE FROM login_tokens WHERE token = ?"
		return withContext(Dispatchers.IO) {
			ConnectionPool.getConnection().use { connection ->
				connection.prepareStatement(sql).use { stmt ->
					stmt.setString(1, token)
					stmt.executeUpdate() > 0
				}
			}
		}
	}

	private fun mapRowToLoginToken(resultSet: ResultSet): LoginToken {
		return LoginToken(
			token = resultSet.getString("token"),
			user = resultSet.getString("user"),
			expires = resultSet.getLong("expires")
		)
	}
}
