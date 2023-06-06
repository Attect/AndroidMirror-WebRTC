package com.example.plugins

import com.example.WebRtcReceiver
import com.example.WebRtcSender
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.time.Duration
import io.ktor.server.application.*
import io.ktor.server.routing.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

var sender: WebRtcSender? = null
var receiverMap: HashMap<String, WebRtcReceiver> = HashMap()
val receiverMapLock = Mutex()
fun Application.configureSockets() {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    routing {
        webSocket("/ws/sender") { // websocketSession
            sender = WebRtcSender(this)
            sender?.handleReceive()
        }
        webSocket("/ws/receiver/{receiverKey}") {
            val key = call.parameters["receiverKey"] ?: throw IllegalArgumentException("未提供receiverKey")
            println("新的接收者 key:$key")
            val receiver = WebRtcReceiver(this, key)
            receiverMapLock.withLock {
                receiverMap[key] = receiver
            }
            receiver.handleReceive()
        }
    }
}

//            for (frame in incoming) {
//                if (frame is Frame.Text) {
//                    val text = frame.readText()
//                    outgoing.send(Frame.Text("YOU SAID: $text"))
//                    if (text.equals("bye", ignoreCase = true)) {
//                        close(CloseReason(CloseReason.Codes.NORMAL, "Client said BYE"))
//                    }
//                }
//            }
