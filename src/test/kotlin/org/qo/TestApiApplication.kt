package org.qo

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration

@SpringBootConfiguration
@EnableAutoConfiguration(
	excludeName = [
		"org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration"
	]
)
class TestApiApplication
