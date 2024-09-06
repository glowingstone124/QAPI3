package org.qo

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
@Component
class CoroutineAdapter {

    fun push(func: Runnable, dispatcher: CoroutineDispatcher) {
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        scope.launch {
            func.run()
        }
    }

    fun <T> run(func: Callable<T>, dispatcher: CoroutineDispatcher): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        scope.launch {
            try {
                val result = func.call()
                future.complete(result)
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }
        return future
    }
}