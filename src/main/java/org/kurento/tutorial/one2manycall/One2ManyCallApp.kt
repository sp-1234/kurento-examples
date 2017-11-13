package org.kurento.tutorial.one2manycall

import org.kurento.client.KurentoClient
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

@SpringBootApplication
@EnableWebSocket
open class One2ManyCallApp : WebSocketConfigurer {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SpringApplication(One2ManyCallApp::class.java).run(*args)
        }
    }

    @Bean
    @Suppress("MemberVisibilityCanPrivate")
    open fun callHandler() = CallHandler()

    @Bean
    @Suppress("unused")
    open fun kurentoClient() = KurentoClient.create()

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(callHandler(), "/call")
    }
}
