package org.qo.datas

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class DatabaseHealth(private val database: ReactiveDatabase) {
	private val available = AtomicBoolean(false)
	private val checking = AtomicBoolean(false)
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

	fun isAvailable(): Boolean = available.get()

	@PostConstruct
	fun refreshOnStartup() {
		refresh()
	}

	@Scheduled(fixedDelay = 5_000)
	fun refresh() {
		if (!checking.compareAndSet(false, true)) return
		scope.launch {
			try {
				available.set(runCatching {
					database.one("SELECT 1") { row -> row.get(0, java.lang.Integer::class.java) } != null
				}.getOrDefault(false))
			} finally {
				checking.set(false)
			}
		}
	}

	@PreDestroy
	fun shutdown() {
		scope.cancel()
	}
}
