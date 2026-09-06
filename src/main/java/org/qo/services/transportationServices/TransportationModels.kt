package org.qo.services.transportationServices

import com.google.gson.JsonArray
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import java.util.Locale

data class Station(
	@SerializedName("name") val NAME: String,
	@SerializedName("id") val ID: String,
	@SerializedName("screen_location") val SCREEN_LOCATION: Array<Location>,
	@SerializedName("name_en") val NAME_EN: String,
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as Station

		if (NAME != other.NAME) return false
		if (ID != other.ID) return false
		if (!SCREEN_LOCATION.contentEquals(other.SCREEN_LOCATION)) return false

		return true
	}

	override fun hashCode(): Int {
		var result = NAME.hashCode()
		result = 31 * result + ID.hashCode()
		result = 31 * result + SCREEN_LOCATION.contentHashCode()
		return result
	}
}

data class Line(
	val stationIds: Array<String>,
	val stationTimes: Array<Int>,
	val lineType: LineType,
	val dimension: Dimension = Dimension.OVERWORLD,
	val name: String,
	val nameEn: String,
	val color: String,
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as Line

		if (!stationIds.contentEquals(other.stationIds)) return false
		if (!stationTimes.contentEquals(other.stationTimes)) return false
		if (lineType != other.lineType) return false
		if (dimension != other.dimension) return false

		return true
	}

	override fun hashCode(): Int {
		var result = stationIds.contentHashCode()
		result = 31 * result + stationTimes.contentHashCode()
		result = 31 * result + lineType.hashCode()
		result = 31 * result + dimension.hashCode()
		return result
	}
}

@JsonAdapter(LocationAdapter::class)
data class Location(
	val x: Double,
	val y: Double,
	val z: Double,
	val world: Dimension,
	val rotation: Int = 0,
)

enum class Dimension(name: String) {
	OVERWORLD("OVERWORLD"),
	NETHER("NETHER"),
	THE_END("THE_END"),
}

class LocationAdapter : JsonSerializer<Location>, JsonDeserializer<Location> {
	override fun serialize(src: Location, typeOfSrc: java.lang.reflect.Type, context: JsonSerializationContext): JsonElement {
		return JsonArray().apply {
			add(worldToCode(src.world))
			add(src.x)
			add(src.y)
			add(src.z)
			add(src.rotation.coerceIn(0, 3))
		}
	}

	override fun deserialize(json: JsonElement, typeOfT: java.lang.reflect.Type, context: JsonDeserializationContext): Location {
		return when {
			json.isJsonArray -> parseTuple(json.asJsonArray)
			json.isJsonObject -> parseObject(json.asJsonObject)
			else -> throw JsonParseException("Location must be an array [world,x,y,z,rotation] or object")
		}
	}

	private fun parseTuple(raw: JsonArray): Location {
		if (raw.size() != 5) throw JsonParseException("Location tuple must contain 5 elements")
		val world = parseWorld(raw[0])
		val x = raw[1].asDouble
		val y = raw[2].asDouble
		val z = raw[3].asDouble
		val rotation = parseRotation(raw[4])
		return Location(x = x, y = y, z = z, world = world, rotation = rotation)
	}

	private fun parseObject(raw: JsonObject): Location {
		val worldElement = raw.get("world") ?: raw.get("dim")
			?: throw JsonParseException("Location object missing world")
		val x = raw.getAsJsonPrimitive("x")?.asDouble ?: throw JsonParseException("Location object missing x")
		val y = raw.getAsJsonPrimitive("y")?.asDouble ?: throw JsonParseException("Location object missing y")
		val z = raw.getAsJsonPrimitive("z")?.asDouble ?: throw JsonParseException("Location object missing z")
		val rotation = parseRotation(raw.get("rotation") ?: JsonPrimitive(0))
		return Location(x = x, y = y, z = z, world = parseWorld(worldElement), rotation = rotation)
	}

	private fun parseWorld(element: JsonElement): Dimension {
		if (!element.isJsonPrimitive) throw JsonParseException("world must be number/string")
		val primitive = element.asJsonPrimitive
		if (primitive.isNumber) return codeToWorld(primitive.asInt)
		val token = primitive.asString.trim()
		val numeric = token.toIntOrNull()
		if (numeric != null) return codeToWorld(numeric)
		return when (
			token
				.substringAfter(':')
				.replace("-", "_")
				.replace(" ", "_")
				.uppercase(Locale.ROOT)
		) {
			"OVERWORLD" -> Dimension.OVERWORLD
			"NETHER" -> Dimension.NETHER
			"THE_END", "THEEND", "END" -> Dimension.THE_END
			else -> throw JsonParseException("Unsupported world value: $token")
		}
	}

	private fun parseRotation(element: JsonElement): Int {
		if (!element.isJsonPrimitive) throw JsonParseException("rotation must be number")
		val value = element.asInt
		if (value !in 0..3) throw JsonParseException("rotation must be 0..3")
		return value
	}

	private fun worldToCode(world: Dimension): Int {
		return when (world) {
			Dimension.OVERWORLD -> 0
			Dimension.NETHER -> -1
			Dimension.THE_END -> 1
		}
	}

	private fun codeToWorld(code: Int): Dimension {
		return when (code) {
			0 -> Dimension.OVERWORLD
			-1 -> Dimension.NETHER
			1 -> Dimension.THE_END
			else -> throw JsonParseException("Unsupported world code: $code")
		}
	}
}

enum class LineType(private val lineType: Int, name: String) {
	METRO(0, "METRO"),
	RAPID(1, "RAPID"),
	BLUEICE(2, "BLUEICE"),
	CITYMETRO(3, "CITYMETRO"),
	NETHER(4, "NETHER"),
	PEARL(5, "PEARL"),
	AIRPLANE(6, "AIRPLANE"),
	BOAT(7, "BOAT"),
	WALK(8, "WALK"),
}

data class LineRecord(
	val id: Int,
	val stationIds: Array<String>,
	val stationTimes: Array<Int>,
	val lineType: LineType,
	val dimension: Dimension = Dimension.OVERWORLD,
	val name: String,
	val color: String,
	val name_en: String,
)

data class LineStations(
	val line: LineRecord,
	val stations: List<Station>,
)

data class TransferLineInfo(
	val id: Int,
	@SerializedName("type") val lineType: LineType,
	val dimension: Dimension,
	val name: String,
	@SerializedName("name_en") val nameEn: String,
	val color: String,
)

data class LineStationDetail(
	val id: String,
	val name: String,
	@SerializedName("name_en") val nameEn: String,
	@SerializedName("transfer_lines") val transferLines: List<TransferLineInfo>,
)

data class LineDetail(
	val line: TransferLineInfo,
	val stations: List<LineStationDetail>,
)

data class RouteConstraints(
	val bannedDimensions: Set<Dimension> = emptySet(),
	val bannedLineTypes: Set<LineType> = emptySet(),
)

data class TransferPoint(
	val stationId: String,
	val fromLineId: Int,
	val toLineId: Int,
)

data class RouteSegment(
	val lineId: Int,
	val lineName: String,
	@SerializedName("name_en") val lineNameEn: String,
	val lineType: LineType,
	val dimension: Dimension = Dimension.OVERWORLD,
	val color: String,
	val stationIds: List<String>,
	val time: Int,
)

data class RouteResult(
	val stationIds: List<String>,
	val stations: List<Station>,
	val lineIds: List<Int>,
	val segments: List<RouteSegment>,
	val transfers: List<TransferPoint>,
	val totalTime: Int,
	val totalStops: Int,
)
