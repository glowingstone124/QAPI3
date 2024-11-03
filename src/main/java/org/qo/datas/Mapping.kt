package org.qo.datas

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
}
