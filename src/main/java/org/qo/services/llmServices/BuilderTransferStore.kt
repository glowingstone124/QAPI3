package org.qo.services.llmServices

import com.google.gson.JsonParser
import org.springframework.stereotype.Component
import java.util.UUID

/** Temporary hand-off only. The browser owns the durable project, not QAPI. */
@Component
class BuilderTransferStore {
    private data class Transfer(val owner: String, val document: String, val expires: Long)
    private val transfers = linkedMapOf<String, Transfer>()

    @Synchronized
    fun put(owner: String, document: String, now: Long = System.currentTimeMillis()): String {
        require(owner.matches(Regex("[A-Za-z0-9_]{3,16}"))) { "Invalid Minecraft name" }
        validateDocument(document)
        transfers.entries.removeIf { it.value.expires <= now }
        // Bound per-player retention without evicting other players' pending imports.
        check(transfers.size < 32 || transfers.values.any { it.owner.equals(owner, ignoreCase = true) }) { "Transfer queue is full" }
        transfers.entries.removeIf { it.value.owner.equals(owner, ignoreCase = true) }
        val code = UUID.randomUUID().toString()
        transfers[code] = Transfer(owner, document, now + 10 * 60 * 1000)
        return code
    }

    @Synchronized
    fun get(code: String, owner: String, now: Long = System.currentTimeMillis()): String? {
        transfers.entries.removeIf { it.value.expires <= now }
        return transfers[code]?.takeIf { it.owner.equals(owner, ignoreCase = true) }?.document
    }

    companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        fun validateDocument(text: String) {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Document too large" }
            val d = JsonParser.parseString(text).asJsonObject
            fun integer(name: String): Int {
                val value = d.get(name)
                require(value?.isJsonPrimitive == true && value.asJsonPrimitive.isNumber) { "Invalid $name" }
                return value.asBigDecimal.intValueExact()
            }
            require(integer("version") == 1)
            require(integer("dataVersion") > 0)
            val dimensions = listOf(integer("width"), integer("height"), integer("length"))
            require(dimensions.all { it in 1..256 } && dimensions.fold(1L) { a, b -> a * b } <= 262144)
            val palette = d.getAsJsonArray("palette")
            require(palette.size() in 1..4096)
            val states = palette.map {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
                it.asString.also { state ->
                    require(state.length <= 256 && state.matches(Regex("[a-z0-9_.-]+:[a-z0-9_./-]+(?:\\[[a-z0-9_]+=[a-z0-9_]+(?:,[a-z0-9_]+=[a-z0-9_]+)*\\])?")))
                }
            }
            require(states.distinct().size == states.size)
            val blocks = d.getAsJsonArray("blocks")
            require(blocks.size() == dimensions.reduce(Int::times))
            blocks.forEach { require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asBigDecimal.intValueExact() in states.indices) }
            for (field in listOf("entities", "blockEntities")) require(!d.has(field) || d.getAsJsonArray(field).isEmpty)
        }
    }
}
