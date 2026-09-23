package org.qo.services.llmServices

import io.r2dbc.spi.R2dbcException
import org.qo.datas.ReactiveDatabase
import org.qo.db.repository.LlmAccessRecordDbRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/** Shared by completion logging and account usage lookup; safe to rerun on existing tables. */
@Component
class LLMAccessRecordSchema @Autowired constructor(
    private val repository: LlmAccessRecordDbRepository,
) {
    constructor(database: ReactiveDatabase) : this(LlmAccessRecordDbRepository(database))

    suspend fun ensure() {
        repository.ensureSchema()
    }
}

internal fun isDuplicateAccessRecordColumn(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.filterIsInstance<R2dbcException>()
        .any { it.errorCode == 1060 || it.sqlState == "42S21" }

internal fun accessRecordSchemaErrorDetail(error: Throwable): String {
    val cause = generateSequence(error) { it.cause }.last()
    return if (cause is R2dbcException) "SQLSTATE=${cause.sqlState} code=${cause.errorCode}: ${cause.message}"
    else cause.message ?: cause.javaClass.simpleName
}
