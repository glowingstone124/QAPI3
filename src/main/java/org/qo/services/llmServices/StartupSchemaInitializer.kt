package org.qo.services.llmServices

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Each startup event runs setup; callers await its first success or failure. */
internal class StartupSchemaInitializer(private val failureMessage: String) : AutoCloseable {
	private val scope = CoroutineScope(SupervisorJob())
	private val ready = CompletableDeferred<Unit>()

	fun start(block: suspend () -> Unit) {
		scope.launch {
			try {
				block()
				ready.complete(Unit)
			} catch (error: Exception) {
				ready.completeExceptionally(error)
				println("$failureMessage: ${error.message}")
			}
		}
	}

	suspend fun await() = ready.await()

	override fun close() = scope.cancel()
}
