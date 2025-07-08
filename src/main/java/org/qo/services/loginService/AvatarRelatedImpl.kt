package org.qo.services.loginService

import org.qo.datas.ConnectionPool
import org.springframework.stereotype.Service

@Service
class AvatarRelatedImpl {
	fun getAvatarUrl(id: String): String? {
		ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement("SELECT url FROM avatars WHERE id = ?").use { preparedStatement ->
				preparedStatement.setString(1, id)
				preparedStatement.executeQuery().use { resultSet ->
					return if (resultSet.next()) {
						resultSet.getString("url")
					} else {
						null
					}
				}
			}
		}
	}
}