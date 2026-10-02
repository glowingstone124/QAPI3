package org.qo.db.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Runs schema setup once per repository instance, retrying after failure or cancellation. */
internal class SchemaInitializer {
	private val mutex = Mutex()
	@Volatile private var ready = false

	suspend fun ensure(block: suspend () -> Unit) {
		if (ready) return
		mutex.withLock {
			if (ready) return
			block()
			ready = true
		}
	}
}
