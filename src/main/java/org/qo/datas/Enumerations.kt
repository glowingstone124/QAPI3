package org.qo.datas

class Enumerations {
	enum class Card_CityCollection_Enum(val id: Int) {
		MAINCITY_1(1),
		MAINCITY_2(2),
		NORZPASHAM(3),
		FIN_TRAINLINE(4),
		ITEM_CAG(5),
		OLYMPUS(6),
		ZINCITY(7),
		TORII(8),
	}
	enum class Card_PixelFantasia_Enum(val id: Int) {
		KOISHI_NORZ(9),
		PATCHOULI_LIB(10),
		PROMETHUS(11),
		FUISLAND(12),
		XMAS(15),
		CHERRY(16)
	}
	enum class Medal_Enum(val id: Int) {
		HOTZONE_CHAMPION(1),
		HOTZONE_RUNNERUP(2),
		HOTZONE_3RDPLACE(3),
		SERVICE_MEDAL_2025(4),
	}
	enum class Card_Collections() {
		CITY_COLLECTION,
		PIXEL_FANTASIA,
		SHINRA_BANSHO
	}

	enum class AdvancementsEnum(val id: Long) {
		ADVANCEMENT_KOISHI(3),
		ADVANCEMENT_PATCHOULI(2),
		ADVANCEMENT_PROMETHUS(4),
		ADVANCEMENT_ORIN(5),
		ADVANCEMENT_WHITE_JADE(6);

		companion object {
			private val map = AdvancementsEnum.entries.associateBy(AdvancementsEnum::id)
			fun fromId(id: Long): AdvancementsEnum? = map[id]
		}
	}
}