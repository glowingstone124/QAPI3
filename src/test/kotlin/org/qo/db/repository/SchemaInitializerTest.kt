package org.qo.db.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SchemaInitializerTest {
	@Test
	fun `concurrent callers wait for one successful initialization`() = runTest {
		val schema = SchemaInitializer()
		val started = CompletableDeferred<Unit>()
		val finish = CompletableDeferred<Unit>()
		var calls = 0
		val first = async {
			schema.ensure {
				calls++
				started.complete(Unit)
				finish.await()
			}
		}
		started.await()
		val waiting = List(8) { async { schema.ensure { calls++ } } }
		yield()
		assertTrue(waiting.none { it.isCompleted })
		finish.complete(Unit)
		(listOf(first) + waiting).awaitAll()
		schema.ensure { error("Already initialized") }
		assertEquals(1, calls)
	}

	@Test
	fun `failed initialization can be retried`() {
		val schema = SchemaInitializer()
		assertThrows(IllegalStateException::class.java) {
			runBlocking { schema.ensure { error("Initialization failed") } }
		}
		var calls = 0
		runBlocking { schema.ensure { calls++ } }
		assertEquals(1, calls)
	}

	@Test
	fun `cancelled initialization releases the lock and can be retried`() = runTest {
		val schema = SchemaInitializer()
		val started = CompletableDeferred<Unit>()
		val first = launch {
			schema.ensure {
				started.complete(Unit)
				awaitCancellation()
			}
		}
		started.await()
		first.cancelAndJoin()
		var calls = 0
		schema.ensure { calls++ }
		assertEquals(1, calls)
	}
}
