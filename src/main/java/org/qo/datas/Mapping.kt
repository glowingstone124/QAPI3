package org.qo.datas

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type
object GsonProvider {
	val gson: Gson = GsonBuilder()
		.registerTypeAdapter(Mapping.CardsRarityEnum::class.java, CardsRarityEnumAdapter)
		.create()
}

class Mapping {

	data class Users(
		val username: String,
		val uid: Long,
		val frozen: Boolean? = false,
		val remain: Int? = 3,
		val economy: Int? = 0,
		val signed: Boolean? = false,
		val playtime: Int? = 0,
		val temp: Boolean?,
		val invite: Int?,
		var password: String,
		val profile_id: String,
	) {
		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (other !is Users) return false

			return username == other.username &&
					uid == other.uid &&
					password == other.password
		}

		fun dumplicate(other: Users): Boolean {
			return uid == other.uid &&
					password == other.password
		}
	}

	data class Cards(
		val name: String,
		val id: Long,
		val special: String,
		val rarity: CardsRarityEnum,
		val file_url: String,
	) {
		override fun toString(): String {
			return GsonProvider.gson.toJson(this).toString()
		}
	}

	enum class CardsRarityEnum(val level: Int) {
		COMMON( 1),
		UNCOMMON( 2),
		RARE(3),
		LIMITED(4)
	}

	data class UserCardRecord(
		val username: String,
		val uid: Long,
		val cardId: Long,
		val obtainedTimeStamp: Long,
	) {
		override fun toString(): String {
			return GsonProvider.gson.toJson(this).toString()
		}
	}

	data class CardProfile (
		val uuid: String,
		val cardId: Long?,
		val statistic1:Int?,
		val statistic2:Int?,
		val statistic3:Int?,
	)
}

object CardsRarityEnumAdapter : JsonSerializer<Mapping.CardsRarityEnum>, JsonDeserializer<Mapping.CardsRarityEnum> {

	override fun serialize(
		src: Mapping.CardsRarityEnum?,
		typeOfSrc: Type?,
		context: JsonSerializationContext?
	): JsonElement {
		return JsonPrimitive(src?.level)
	}

	override fun deserialize(
		json: JsonElement?,
		typeOfT: Type?,
		context: JsonDeserializationContext?
	): Mapping.CardsRarityEnum {
		val level = json?.asInt ?: throw JsonParseException("Rarity level required")
		return Mapping.CardsRarityEnum.entries.firstOrNull { it.level == level }
			?: throw JsonParseException("Unknown rarity level: $level")
	}
}
