package org.qo.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.springframework.stereotype.Component
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration

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

    fun setInterval(func: Runnable, interval: Duration, dispatcher: CoroutineDispatcher): Job {
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        return scope.launch {
            while (isActive) {
	            delay(interval)
                try {
                    func.run()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun clearInterval(job: Job) {
        job.cancel()
    }
}