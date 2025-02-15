package org.qo.orm

import com.google.gson.Gson
import io.asyncer.r2dbc.mysql.MySqlConnectionConfiguration
import io.asyncer.r2dbc.mysql.MySqlConnectionFactory
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Paths

object SQL {
    private lateinit var _configuration: MySqlConnectionConfiguration
    val configuration: MySqlConnectionConfiguration
        get() {
            if (!::_configuration.isInitialized) {
                initialize()
            }
            return _configuration
        }

    val connectionFactory: MySqlConnectionFactory by lazy { MySqlConnectionFactory.from(configuration) }
    private fun initialize() {
        try {
            val content = Files.readString(Paths.get("data/sql/info.json"))
            val sqlInfo = Gson().fromJson(content, SQLInfo::class.java)
            val urlInfo = parseJdbcUrl(sqlInfo.url)

            if (urlInfo == null) {
                throw IllegalArgumentException("Invalid JDBC URL format: ${sqlInfo.url}")
            }
            println("Parsed URL Info: $urlInfo")

            _configuration = MySqlConnectionConfiguration.builder()
                .username(sqlInfo.username)
                .password(sqlInfo.password)
                .host(urlInfo.first)
                .port(urlInfo.second)
                .database(urlInfo.third)
                .build()
        } catch (e: Exception) {
            println("Error initializing SQL configuration: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun parseJdbcUrl(jdbcUrl: String): Triple<String, Int, String>? {
        val regex = Regex("jdbc:mysql://([^:/]+)(?::(\\d+))?/(\\w+)")
        val matchResult = regex.matchEntire(jdbcUrl)
        return matchResult?.let {
            val host = it.groupValues[1]
            val port = it.groupValues[2].toIntOrNull() ?: 3306
            val database = it.groupValues[3]
            Triple(host, port, database)
        }
    }

    data class SQLInfo(val username: String, val password: String, val url: String)

    suspend fun getConnection() = connectionFactory.create().awaitSingle()
}

@Configuration
class DBConfig {

    @Bean
    fun connectionFactory(): MySqlConnectionFactory {
        return SQL.connectionFactory
    }

}
