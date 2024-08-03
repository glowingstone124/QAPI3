package org.qo.orm
interface CrudDao<T> {
    fun create(item: T): Long
    fun read(id: Long): T?
    fun update(item: T): Boolean
    fun delete(id: Long): Boolean
}
