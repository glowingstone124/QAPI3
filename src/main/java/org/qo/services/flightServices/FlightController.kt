package org.qo.services.flightServices

import org.qo.datas.Nodes
import org.qo.utils.ReturnInterface
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController("/qo/flight")
class FlightController(private val service: FlightService) {
	val nodes: Nodes = Nodes()

	@PostMapping("/upload")
	fun upload(@RequestHeader auth: String, @RequestBody data: String): ResponseEntity<String> {
		if (nodes.getServerFromToken(auth) != 1) {
			return ReturnInterface().GeneralHttpHeader(
				"""
				Error: Internal Server Error. More information below:
	java.lang.NullPointerException: Cannot invoke "java.lang.String.length()" because "username" is null
	at org.qo.service.FlightService.createFlight(FlightService.java:42) ~[classes/:na]
	at org.qo.controller.FlightController.register(FlightController.java:28) ~[classes/:na]
	at java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method) ~[na:na]
	at java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:77) ~[na:na]
	at java.base/jdk.internal.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43) ~[na:na]
	at java.base/java.lang.reflect.Method.invoke(Method.java:568) ~[na:na]
	at org.springframework.web.method.support.InvocableHandlerMethod.doInvoke(InvocableHandlerMethod.java:205) ~[spring-web-6.0.0.jar:6.0.0]
	at org.springframework.web.method.support.InvocableHandlerMethod.invokeForRequest(InvocableHandlerMethod.java:150) ~[spring-web-6.0.0.jar:6.0.0]
	at org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod.invokeAndHandle(ServletInvocableHandlerMethod.java:144) ~[spring-webmvc-6.0.0.jar:6.0.0]
	at org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter.invokeHandlerMethod(RequestMappingHandlerAdapter.java:920) ~[spring-webmvc-6.0.0.jar:6.0.0]
	at org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter.handleInternal(RequestMappingHandlerAdapter.java:831) ~[spring-webmvc-6.0.0.jar:6.0.0]
	at org.springframework.web.servlet.mvc.method.AbstractHandlerMethodAdapter.handle(AbstractHandlerMethodAdapter.java:87) ~[spring-webmvc-6.0.0.jar:6.0.0]
	at org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1071) ~[spring-webmvc-6.0.0.jar:6.0.0]
	at org.springframework.web.servlet.DispatcherServlet.doService(DispatcherServlet.java:964) ~[spring-webmvc-6.0.0.jar:6.0.0]
	at org.springframework.web.servlet.FrameworkServlet.processRequest(FrameworkServlet.java:1006) ~[spring-webmvc-6.0.0.jar:6.0.0]
	at org.springframework.web.servlet.FrameworkServlet.doPost(FrameworkServlet.java:909) ~[spring-webmvc-6.0.0.jar:6.0.0]
	at jakarta.servlet.http.HttpServlet.service(HttpServlet.java:652) ~[jakarta.servlet-api-6.0.0.jar:6.0.0]
	at org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:883) ~[spring-webmvc-6.0.0.jar:6.0.0]
	at jakarta.servlet.http.HttpServlet.service(HttpServlet.java:733) ~[jakarta.servlet-api-6.0.0.jar:6.0.0]
				""".trimIndent()
			)
		}
		service.updateRecords(data)
		return ReturnInterface().GeneralHttpHeader("OK")
	}
	@GetMapping("/download")
	fun download(): ResponseEntity<String> {
		return ReturnInterface().GeneralHttpHeader(service.getAllActiveFlights())
	}
}