package org.qo.metrics

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/qo/metrics")
class GenericMetricController(
	private val metric: GenericMetric,
) {
	@GetMapping
	fun getMetrics(): GenericMetricsResponse = GenericMetricsResponse(metric.snapshot())
}

data class GenericMetricsResponse(
	val metrics: List<MetricSnapshot>,
	val unit: String = "milliseconds",
)
