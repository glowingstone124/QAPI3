package org.qo.orm

import org.qo.datas.Mapping.Users
import org.qo.ConnectionPool
import java.sql.PreparedStatement

class UserORM : CrudDao<Users> {

    override fun create(user: Users): Long {
        val sql = "INSERT INTO users (username, uid, frozen, remain, economy, signed, playtime, password) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        val statement: PreparedStatement = ConnectionPool.getConnection().prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)
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
        return if (keys.next()) keys.getLong(1) else -1
    }

    override fun read(id: Long): Users? {
        val sql = "SELECT * FROM users WHERE uid = ?"
        val statement = ConnectionPool.getConnection().prepareStatement(sql)
        statement.setLong(1, id)
        val resultSet = statement.executeQuery()
        return if (resultSet.next()) {
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

    override fun update(user: Users): Boolean {
        val sql = "UPDATE users SET username = ?, frozen = ?, remain = ?, economy = ?, signed = ?, playtime = ?, password = ? WHERE uid = ?"
        val statement = ConnectionPool.getConnection().prepareStatement(sql)
        statement.setString(1, user.username)
        statement.setBoolean(2, user.frozen ?: false)
        statement.setInt(3, user.remain ?: 3)
        statement.setInt(4, user.economy ?: 0)
        statement.setBoolean(5, user.signed ?: false)
        statement.setInt(6, user.playtime ?: 0)
        statement.setString(7, user.password)
        statement.setLong(8, user.uid)
        return statement.executeUpdate() > 0
    }

    override fun delete(id: Long): Boolean {
        val sql = "DELETE FROM users WHERE uid = ?"
        val statement = ConnectionPool.getConnection().prepareStatement(sql)
        statement.setLong(1, id)
        return statement.executeUpdate() > 0
    }
}