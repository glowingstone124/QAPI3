package org.qo.orm

import org.qo.datas.Mapping.Users
import org.qo.ConnectionPool
import java.sql.PreparedStatement

class UserORM : CrudDao<Users> {

    override fun create(user: Users): Long {
        val sql = "INSERT INTO users (username, uid, frozen, remain, economy, signed, playtime, password) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        return ConnectionPool.getConnection().use { connection ->
            connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS).use { statement ->
                statement.setString(1, user.username)
                statement.setLong(2, user.uid)
                statement.setBoolean(3, user.frozen ?: false)
                statement.setInt(4, user.remain ?: 3)
                statement.setInt(5, user.economy ?: 0)
                statement.setBoolean(6, user.signed ?: false)
                statement.setInt(7, user.playtime ?: 0)
                statement.setString(8, user.password)
                statement.executeUpdate()
                val keys = statement.generatedKeys
                if (keys.next()) keys.getLong(1) else -1
            }
        }
    }

    override fun read(input: Any): Users? {
        val sql = when (input) {
            is Long -> "SELECT * FROM users WHERE uid = ?"
            is String -> "SELECT * FROM users WHERE username = ?"
            else -> throw IllegalArgumentException("Query for User class can only be String or Long")
        }
        return ConnectionPool.getConnection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                if (input is Long) {
                    statement.setLong(1, input)
                } else if (input is String) {
                    statement.setString(1, input)
                }
                val resultSet = statement.executeQuery()
                if (resultSet.next()) {
                    Users(
                        username = resultSet.getString("username"),
                        uid = resultSet.getLong("uid"),
                        frozen = resultSet.getBoolean("frozen"),
                        remain = resultSet.getInt("remain"),
                        economy = resultSet.getInt("economy"),
                        signed = resultSet.getBoolean("signed"),
                        playtime = resultSet.getInt("playtime"),
                        password = resultSet.getString("password")
                    )
                } else {
                    null
                }
            }
        }
    }

    override fun update(user: Users): Boolean {
        val fields = mutableListOf<String>()
        val values = mutableListOf<Any?>()

        user.username?.let {
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

        if (fields.isEmpty()) return false

        val sql = "UPDATE users SET ${fields.joinToString(", ")} WHERE uid = ?"
        values.add(user.uid)

        return ConnectionPool.getConnection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                values.forEachIndexed { index, value ->
                    when (value) {
                        is String -> statement.setString(index + 1, value)
                        is Boolean -> statement.setBoolean(index + 1, value)
                        is Int -> statement.setInt(index + 1, value)
                        is Long -> statement.setLong(index + 1, value)
                        else -> throw IllegalArgumentException("Unsupported data type")
                    }
                }
                statement.executeUpdate() > 0
            }
        }
    }


    override fun delete(input: Any): Boolean {
        val (sql, paramSetter) = when (input) {
            is String -> "DELETE FROM users WHERE username = ?" to { statement: PreparedStatement ->
                statement.setString(1, input)
            }
            is Long -> "DELETE FROM users WHERE uid = ?" to { statement: PreparedStatement ->
                statement.setLong(1, input)
            }
            else -> throw IllegalArgumentException("Input must be either a String or a Long")
        }

        return ConnectionPool.getConnection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                paramSetter(statement)
                statement.executeUpdate() > 0
            }
        }
    }

}
