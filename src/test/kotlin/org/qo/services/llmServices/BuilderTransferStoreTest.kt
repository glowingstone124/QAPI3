package org.qo.services.llmServices

import kotlin.test.*
import org.junit.jupiter.api.Test

class BuilderTransferStoreTest {
    private val document = """{"version":1,"dataVersion":3955,"width":2,"height":1,"length":1,"palette":["minecraft:air","minecraft:stone"],"blocks":[0,1]}"""

    @Test fun `transfer is owner scoped and expires without being consumed on retry`() {
        val store = BuilderTransferStore()
        val code = store.put("Alice", document, 100)
        assertNull(store.get(code, "Bob", 101))
        assertEquals(document, store.get(code, "alice", 102))
        assertEquals(document, store.get(code, "Alice", 103))
        assertNull(store.get(code, "Alice", 600100))
    }

    @Test fun `new export replaces the same player's previous handoff`() {
        val store = BuilderTransferStore()
        val old = store.put("Alice", document, 1)
        val fresh = store.put("Alice", document, 2)
        assertNull(store.get(old, "Alice", 3))
        assertEquals(document, store.get(fresh, "Alice", 3))
    }

    @Test fun `invalid block indices fractional dimensions and unbounded selections are rejected`() {
        for (bad in listOf(
            document.replace("[0,1]", "[0,2]"),
            document.replace("\"width\":2", "\"width\":2.5"),
            document.replace("\"width\":2", "\"width\":257"),
            document.replace("minecraft:stone", "invalid state"),
            document.replace("[0,1]", "[0]"),
        )) assertFails { BuilderTransferStore.validateDocument(bad) }
    }
}
