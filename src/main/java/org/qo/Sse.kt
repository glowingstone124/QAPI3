package org.qo

import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException

@RestController
@RequestMapping("/qo/sse")
class SseController {
    private val emitters: MutableList<SseEmitter> = mutableListOf()

    @GetMapping(value = ["/playerquery"], produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamSse(): SseEmitter {
        val emitter = SseEmitter()
        emitters.add(emitter)
        emitter.onCompletion { emitters.remove(emitter) }
        emitter.onTimeout { emitters.remove(emitter) }
        return emitter
    }

    @EventListener
    fun handleSseEvent(event: SseEvent) {
        val deadEmitters: MutableList<SseEmitter> = mutableListOf()
        for (emitter in emitters) {
            try {
                emitter.send(SseEmitter.event().data(event.message))
            } catch (e: IOException) {
                deadEmitters.add(emitter)
            }
        }
        emitters.removeAll(deadEmitters)
    }
}

class SseEvent(source: Any, val message: String) : ApplicationEvent(source)

@Service
class SseService(private val eventPublisher: ApplicationEventPublisher) {
    fun sendEvent(message: String) {
        val sseEvent = SseEvent(this, message)
        eventPublisher.publishEvent(sseEvent)
    }
}

object newClass {
    @JvmStatic
    fun staticFunction() {

    }
}