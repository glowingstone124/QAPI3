package org.qo.services.llmServices

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

@Component
class AfdianOrderRetryWorker(private val credits: AfdianCreditsService) {
    private val logger = LoggerFactory.getLogger(AfdianOrderRetryWorker::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    fun retry() {
        if (!running.compareAndSet(false,true)) return
        scope.launch {
            try {
                credits.retryPendingOrders()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                logger.warn("Afdian durable retry unavailable ({})",error.javaClass.simpleName)
            } finally {
                running.set(false)
            }
        }
    }

    @PreDestroy fun close() = scope.cancel()
}
