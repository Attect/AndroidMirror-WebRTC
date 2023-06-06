package com.example

import com.example.plugins.receiverMap
import com.example.plugins.receiverMapLock
import com.example.plugins.sender
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.lang.Exception
import kotlin.coroutines.CoroutineContext

class WebRtcReceiver(val webSocketSession: DefaultWebSocketSession, val key: String) : CoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default
    var isAvailable = true
        private set

    suspend fun handleReceive(): Job = coroutineScope {
        println("接收端连入")
        launch {
            if (sender?.isAvailable == true) {
                sender?.receiverOnline(key)
                deviceOnline()
                println("send device-online")
            }
            try {
                while (isActive) {
                    val frame = webSocketSession.incoming.receive()
                    if (frame is Frame.Text) {
                        val receiveContent = frame.readText()
                        val command = receiveContent.substringBefore("|")
                        val dataString = receiveContent.substringAfter("|")
                        when (command) {
                            "answer" -> {
                                sender?.answerTo(key, dataString)
                            }

                            "onIceCandidate" -> {
                                sender?.onIceCandidate(key, dataString)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isAvailable = false
            receiverMapLock.withLock {
                receiverMap.remove(key)
            }
            sender?.receiverOffline(key)

            println("接收端WS关闭 key:$key")
        }
    }

    private suspend fun send(command: String, data: String = "") {
        webSocketSession.send("$command|$data")
    }

    suspend fun deviceOnline() {
        send("device-online")
    }

    suspend fun deviceOffline() {
        send("device-offline")
    }

    suspend fun offerTo(sdpData: String) {
        send("offer", sdpData)
    }

    suspend fun onIceCandidate(data: String) {
        send("onIceCandidate", data)
    }

}