package com.example

import com.example.data.IceCandidateData
import com.example.plugins.receiverMap
import com.example.plugins.receiverMapLock
import com.example.plugins.sender
import com.google.gson.Gson
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.lang.Exception
import kotlin.coroutines.CoroutineContext

class WebRtcSender(val webSocketSession: DefaultWebSocketSession) : CoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default

    var isAvailable = true
        private set

    val gson = Gson()

    suspend fun handleReceive(): Job = coroutineScope {
        println("发送端连入")
        launch {
            receiverMapLock.withLock {
                receiverMap.forEach { (_, receiver) ->
                    launch(Dispatchers.IO) {
                        receiver.deviceOnline()
                        send("receiver-online", receiver.key)
                    }
                }
            }
            try {
                while (isActive) {
                    val frame = webSocketSession.incoming.receive()
                    if (frame is Frame.Text) {
                        val receiveContent = frame.readText()
                        val command = receiveContent.substringBefore("|")
                        val clientKey = receiveContent.substring(command.length + 1).substringBefore("|")
                        val dataString = receiveContent.substring(command.length + clientKey.length + 2).substringAfter("|")
                        when (command) {
                            "offer" -> {
                                receiverMapLock.withLock {
                                    receiverMap[clientKey]?.offerTo(dataString)
                                }
                            }

                            "onIceCandidate" -> {
                                receiverMapLock.withLock {
                                    receiverMap[clientKey]?.onIceCandidate(dataString)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            receiverMapLock.withLock {
                receiverMap.forEach { (_, receiver) ->
                    launch(Dispatchers.IO) {
                        receiver.deviceOffline()
                    }
                }
            }
            println("发送端WS关闭")
            isAvailable = false
            sender = null

        }
    }

    suspend private fun send(command: String, clientKey: String, data: String = "") {
        webSocketSession.send("$command|$clientKey|$data")
    }

    suspend fun receiverOnline(key: String) {
        send("receiver-online", key)
    }

    suspend fun receiverOffline(key: String) {
        send("receiver-offline", key)
    }

    suspend fun answerTo(clientKey: String, sdpData: String) {
        send("answer", clientKey, sdpData)
    }

    suspend fun onIceCandidate(clientKey: String, data: String) {
        send("onIceCandidate", clientKey, data)
    }

}