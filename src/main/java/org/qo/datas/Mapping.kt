package org.qo.datas

import com.google.gson.Gson
val gson = Gson()
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
        var password: String
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Users) return false

            return username == other.username &&
                    uid == other.uid &&
                    password == other.password
        }
        fun dumplicate(other: Users): Boolean {
            return  uid == other.uid &&
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
            return gson.toJson(this).toString()
        }
    }

    enum class CardsRarityEnum(val displayName: String, val level: Int) {
        COMMON("common", 1),
        UNCOMMON("uncommon", 2),
        RARE("rare", 3),
        LIMITED("limited", 4)
    }

    data class UserCardRecord(
        val username: String,
        val uid: Long,
        val cardId: Long,
        val obtainedTimeStamp: Long,
    ) {
        override fun toString(): String {
            return gson.toJson(this).toString()
        }
    }

}
