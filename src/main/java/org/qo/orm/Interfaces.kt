package org.qo.orm

import org.qo.ConnectionPool

interface CrudDao<T> {
    fun create(item: T): Long
    fun read(input: Any): T?
    fun update(item: T): Boolean
    fun delete(input: Any): Boolean
}
