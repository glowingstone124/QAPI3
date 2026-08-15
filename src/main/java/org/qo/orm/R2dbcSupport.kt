package org.qo.orm

import org.qo.datas.ReactiveDatabase
import org.qo.utils.SpringContextUtil

internal fun reactiveDatabase(databaseOverride: ReactiveDatabase?): ReactiveDatabase =
	databaseOverride ?: SpringContextUtil.ctx.getBean(ReactiveDatabase::class.java)

internal fun unsupportedSyncApi(api: String): Nothing =
	throw UnsupportedOperationException("$api requires a suspend/R2DBC caller")

internal fun intValue(value: Any?): Int? = when (value) {
	null -> null
	is Int -> value
	is Long -> value.toInt()
	is Short -> value.toInt()
	is Byte -> value.toInt()
	is Number -> value.toInt()
	is String -> value.toIntOrNull()
	is Boolean -> if (value) 1 else 0
	else -> null
}

internal fun longValue(value: Any?): Long? = when (value) {
	null -> null
	is Long -> value
	is Int -> value.toLong()
	is Short -> value.toLong()
	is Byte -> value.toLong()
	is Number -> value.toLong()
	is String -> value.toLongOrNull()
	is Boolean -> if (value) 1L else 0L
	else -> null
}

internal fun booleanValue(value: Any?): Boolean? = when (value) {
	null -> null
	is Boolean -> value
	is Number -> value.toInt() != 0
	is String -> when (value.lowercase()) {
		"1", "true", "t", "yes", "y" -> true
		"0", "false", "f", "no", "n" -> false
		else -> null
	}
	else -> null
}
