package org.qo.orm

import org.qo.ConnectionPool

interface CrudDao<T> {
    companion object {
        private const val COUNT_USERS_SQL = "SELECT COUNT(*) AS total FROM users"
    }
    fun create(item: T): Long
    fun read(input: Any): T?
    fun update(item: T): Boolean
    fun delete(input: Any): Boolean
    fun count(): Long {
        return ConnectionPool.getConnection().use { connection ->
            connection.prepareStatement(COUNT_USERS_SQL).use { stmt ->
                val rs = stmt.executeQuery()
                if (rs.next()) rs.getLong("total") else 0L
            }
        }
    }
}
