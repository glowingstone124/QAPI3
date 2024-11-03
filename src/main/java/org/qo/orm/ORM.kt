package org.qo.orm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.qo.datas.Mapping.Users
import org.qo.ConnectionPool
import org.qo.UserProcess.computePassword
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.sql.PreparedStatement
import java.sql.ResultSet

class UserORM() : CrudDao<Users>  {
    fun count(): Long = runBlocking {
        /*return ConnectionPool.getConnection().use { connection ->
            connection.prepareStatement(COUNT_USERS_SQL).use { stmt ->
                val rs = stmt.executeQuery()
                if (rs.next()) rs.getLong("total") else 0L
            }
        }*/
        val result = SQL.getConnection().createStatement(COUNT_USERS_SQL).execute().awaitSingle()
        result.map { row, _ ->
            row.get("total", Long::class.java) ?: 0L
        }.awaitSingle()
    }

    companion object {
        private const val INSERT_USER_SQL =
            "INSERT INTO users (username, uid, frozen, remain, economy, signed, playtime, password, temp, invite) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        private const val SELECT_USER_BY_ID_SQL = "SELECT * FROM users WHERE uid = ?"
        private const val SELECT_USER_BY_USERNAME_SQL = "SELECT * FROM users WHERE username = ?"
        private const val DELETE_USER_BY_ID_SQL = "DELETE FROM users WHERE uid = ?"
        private const val DELETE_USER_BY_USERNAME_SQL = "DELETE FROM users WHERE username = ?"
        private const val COUNT_USERS_SQL = "SELECT COUNT(*) AS total FROM users"
    }

    override fun create(user: Users): Long = runBlocking {
        withContext(Dispatchers.IO) {
            return@withContext ConnectionPool.getConnection().use { connection ->
                connection.prepareStatement(INSERT_USER_SQL, PreparedStatement.RETURN_GENERATED_KEYS).use { stmt ->
                    setStatementParams(stmt, user)
                    stmt.executeUpdate()
                    val keys = stmt.generatedKeys
                    if (keys.next()) keys.getLong(1) else -1
                }
            }
        }
    }

    override fun read(input: Any): Users? = runBlocking {
        withContext(Dispatchers.IO) {
            val (sql, paramSetter) = when (input) {
                is Long -> SELECT_USER_BY_ID_SQL to fun(stmt: PreparedStatement) { stmt.setLong(1, input) }
                is String -> SELECT_USER_BY_USERNAME_SQL to fun(stmt: PreparedStatement) { stmt.setString(1, input) }
                else -> throw IllegalArgumentException("Input must be either a String or a Long")
            }
            return@withContext ConnectionPool.getConnection().use { connection ->
                connection.prepareStatement(sql).use { stmt ->
                    paramSetter(stmt)
                    val rs = stmt.executeQuery()
                    if (rs.next()) mapResultSetToUser(rs) else null
                }
            }
        }
    }
    fun updatePassword(uid: Long, newPassword: String): Boolean = runBlocking {
        withContext(Dispatchers.IO) {
            try {
                val sql = "UPDATE users SET password = ? WHERE uid = ?"
                return@withContext ConnectionPool.getConnection().use { connection ->
                    connection.prepareStatement(sql).use { stmt ->
                        stmt.setString(1, newPassword)
                        stmt.setLong(2, uid)
                        stmt.executeUpdate() > 0
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext false
            }
        }
    }

    override fun update(user: Users): Boolean = runBlocking {
        withContext(Dispatchers.IO) {
            val fields = mutableListOf<String>()
            val values = mutableListOf<Any?>()
            user.username.let {
                fields.add("username = ?")
                values.add(it)
            }
            user.frozen?.let {
                fields.add("frozen = ?")
                values.add(it)
            }
            user.remain?.let {
                fields.add("remain = ?")
                values.add(it)
            }
            user.economy?.let {
                fields.add("economy = ?")
                values.add(it)
            }
            user.signed?.let {
                fields.add("signed = ?")
                values.add(it)
            }
            user.playtime?.let {
                fields.add("playtime = ?")
                values.add(it)
            }
            user.password?.let {
                fields.add("password = ?")
                values.add(it)
            }
            user.temp?.let {
                fields.add("temp = ?")
                values.add(it)
            }
            user.invite?.let {
                fields.add("invite = ?")
                values.add(it)
            }
            if (fields.isEmpty()) return@withContext false
            val sql = "UPDATE users SET ${fields.joinToString(", ")} WHERE uid = ?"
            values.add(user.uid)
            return@withContext ConnectionPool.getConnection().use { connection ->
                connection.prepareStatement(sql).use { stmt ->
                    setParamValues(stmt, values)
                    stmt.executeUpdate() > 0
                }
            }
        }
    }

    override fun delete(input: Any): Boolean = runBlocking {
        withContext(Dispatchers.IO) {
            val (sql, paramSetter) = when (input) {
                is Long -> DELETE_USER_BY_ID_SQL to { stmt: PreparedStatement -> stmt.setLong(1, input) }
                is String -> DELETE_USER_BY_USERNAME_SQL to { stmt: PreparedStatement -> stmt.setString(1, input) }
                else -> throw IllegalArgumentException("Input must be either a String or a Long")
            }
            return@withContext ConnectionPool.getConnection().use { connection ->
                connection.prepareStatement(sql).use { stmt ->
                    paramSetter(stmt)
                    stmt.executeUpdate() > 0
                }
            }
        }
    }

    private suspend fun setStatementParams(stmt: PreparedStatement, user: Users) = withContext(Dispatchers.IO) {
        stmt.setString(1, user.username)
        stmt.setLong(2, user.uid)
        stmt.setBoolean(3, user.frozen == true)
        stmt.setInt(4, user.remain ?: 3)
        stmt.setInt(5, user.economy ?: 0)
        stmt.setBoolean(6, user.signed == true)
        stmt.setInt(7, user.playtime ?: 0)
        stmt.setString(8, user.password)
        stmt.setBoolean(9, user.temp == true)
        stmt.setInt(10, user.invite ?: 0)
    }

    private suspend fun mapResultSetToUser(rs: ResultSet): Users = withContext(Dispatchers.IO) {
        return@withContext Users(
            username = rs.getString("username"),
            uid = rs.getLong("uid"),
            frozen = rs.getBoolean("frozen"),
            remain = rs.getInt("remain"),
            economy = rs.getInt("economy"),
            signed = rs.getBoolean("signed"),
            playtime = rs.getInt("playtime"),
            password = rs.getString("password"),
            temp = rs.getBoolean("temp"),
            invite = rs.getInt("invite")
        )
    }

    private suspend fun setParamValues(stmt: PreparedStatement, values: MutableList<Any?>) =
        withContext(Dispatchers.IO) {
            for ((index, value) in values.withIndex()) {
                when (value) {
                    is String -> stmt.setString(index + 1, value)
                    is Boolean -> stmt.setBoolean(index + 1, value)
                    is Int -> stmt.setInt(index + 1, value)
                    is Long -> stmt.setLong(index + 1, value)
                    else -> throw IllegalArgumentException("Unsupported data type")
                }
            }
        }

}