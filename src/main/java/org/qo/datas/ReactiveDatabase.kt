package org.qo.datas

import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import org.springframework.transaction.reactive.TransactionalOperator

@Component
class ReactiveDatabase(
	private val client: DatabaseClient,
	private val transactionalOperator: TransactionalOperator,
) {
	suspend fun execute(sql: String, bindings: List<Any?> = emptyList()): Long =
		bind(client.sql(sql), bindings).fetch().rowsUpdated().awaitSingle()

	suspend fun <T> one(
		sql: String,
		bindings: List<Any?> = emptyList(),
		mapper: (Row) -> T,
	): T? = bind(client.sql(sql), bindings)
		.map { row, _ -> mapper(row) }
		.one()
		.awaitSingleOrNull()

	suspend fun <T> all(
		sql: String,
		bindings: List<Any?> = emptyList(),
		mapper: (Row) -> T,
	): List<T> = bind(client.sql(sql), bindings)
		.map { row, _ -> mapper(row) }
		.all()
		.collectList()
		.awaitSingle()

	suspend fun <T> inTransaction(block: suspend () -> T): T =
		transactionalOperator.execute {
			mono { TransactionValue(block()) }
		}.single().awaitSingle().value

	private data class TransactionValue<T>(val value: T)

	private fun bind(
		spec: DatabaseClient.GenericExecuteSpec,
		bindings: List<Any?>,
	): DatabaseClient.GenericExecuteSpec {
		var bound = spec
		bindings.forEachIndexed { index, value ->
			bound = if (value == null) {
				bound.bindNull(index, Any::class.java)
			} else {
				bound.bind(index, value)
			}
		}
		return bound
	}
}
