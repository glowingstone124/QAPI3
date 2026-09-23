package org.qo.db.repository

import org.qo.datas.Mapping
import org.qo.datas.ReactiveDatabase
import org.springframework.stereotype.Repository

@Repository
class AvatarDbRepository(
	private val database: ReactiveDatabase,
) {
	suspend fun getAvatarUrl(id: String): String? = database.one(
		"SELECT url FROM avatars WHERE id = ?",
		listOf(id),
	) { row -> row.get("url", String::class.java) }

	suspend fun doesAvatarExist(id: String): Boolean =
		database.one("SELECT url FROM avatars WHERE id = ?", listOf(id)) { true } != null

	suspend fun getAllAvatars(): List<Mapping.Avatar> = database.all("SELECT * FROM avatars") { row ->
		Mapping.Avatar(
			id = row.get("id", String::class.java).orEmpty(),
			url = row.get("url", String::class.java).orEmpty(),
		)
	}
}
