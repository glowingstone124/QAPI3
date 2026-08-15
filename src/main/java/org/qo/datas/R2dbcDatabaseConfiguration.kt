package org.qo.datas

import com.google.gson.JsonParser
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ResourceLoader
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import java.time.Duration

@Configuration(proxyBeanMethods = false)
class R2dbcDatabaseConfiguration(
	private val resourceLoader: ResourceLoader,
	@param:Value("\${qapi.database.config-location:file:data/sql/info.json}")
	private val configLocation: String,
) {
	@Bean(destroyMethod = "dispose")
	fun connectionFactory(): ConnectionPool {
		val settings = connectionSettings()
		val options = ConnectionFactoryOptions.parse(settings.url).mutate()
			.option(ConnectionFactoryOptions.USER, settings.username)
			.option(ConnectionFactoryOptions.PASSWORD, settings.password)
			.build()
		val factory = ConnectionFactories.get(options)
		return ConnectionPool(
			ConnectionPoolConfiguration.builder(factory)
				.initialSize(INITIAL_SIZE)
				.minIdle(MIN_IDLE)
				.maxSize(MAX_SIZE)
				.maxAcquireTime(Duration.ofMillis(MAX_ACQUIRE_TIME_MS))
				.backgroundEvictionInterval(Duration.ofMillis(EVICTION_INTERVAL_MS))
				.build()
		)
	}

	@Bean
	fun databaseClient(connectionFactory: ConnectionFactory): DatabaseClient =
		DatabaseClient.builder().connectionFactory(connectionFactory).build()

	@Bean
	fun reactiveTransactionManager(connectionFactory: ConnectionFactory): ReactiveTransactionManager =
		R2dbcTransactionManager(connectionFactory)

	@Bean
	fun transactionalOperator(transactionManager: ReactiveTransactionManager): TransactionalOperator =
		TransactionalOperator.create(transactionManager)

	private fun connectionSettings(): ConnectionSettings {
		val resource = resourceLoader.getResource(configLocation)
		require(resource.exists()) {
			"Missing database configuration: $configLocation"
		}
		val root = resource.inputStream.bufferedReader().use(JsonParser::parseReader).asJsonObject
		val configuredUrl = root.get("url")?.asString?.trim().orEmpty()
		val username = root.get("username")?.asString.orEmpty()
		val password = root.get("password")?.asString.orEmpty()
		require(configuredUrl.startsWith("jdbc:") || configuredUrl.startsWith("r2dbc:")) {
			"Database URL in $configLocation must start with jdbc: or r2dbc:"
		}
		return ConnectionSettings(
			url = if (configuredUrl.startsWith("jdbc:")) {
				"r2dbc:" + configuredUrl.removePrefix("jdbc:")
			} else {
				configuredUrl
			},
			username = username,
			password = password,
		)
	}

	private companion object {
		const val INITIAL_SIZE = 5
		const val MIN_IDLE = 5
		const val MAX_SIZE = 100
		const val MAX_ACQUIRE_TIME_MS = 3_000L
		const val EVICTION_INTERVAL_MS = 60_000L
	}

	private data class ConnectionSettings(
		val url: String,
		val username: String,
		val password: String,
	)
}
