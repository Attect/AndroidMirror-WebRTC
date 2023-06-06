package studio.attect.webrtc.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import studio.attect.webrtc.ScreenStreamManager
import studio.attect.webrtc.SimplePeerConnectionObserver
import studio.attect.webrtc.SimpleSessionDescriptionObserver
import kotlin.coroutines.CoroutineContext

class StreamService : Service(), CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default

    var running = false
        private set
    private val binder = StreamServiceBinder(this)

    var isWebSocketConnected by mutableStateOf(false)

    private val mConfigureIntent: PendingIntent by lazy {
        var activityFlag = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activityFlag += PendingIntent.FLAG_MUTABLE
        }
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), activityFlag)
    }

    private val windowManager: WindowManager by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private val currentDisplay: Display by lazy { windowManager.defaultDisplay }
    var mediaProjectionIntent: Intent? = null

    private lateinit var screenStreamManager: ScreenStreamManager
    private val okHttpClient = OkHttpClient()
    private lateinit var webSocket: WebSocket
    private lateinit var websocketUrl: String
    private val gson = Gson()

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        running = false
    }

    fun start() {
        if (running) throw IllegalStateException("已经运行了")
        running = true
        createNotification()
        screenStreamManager = ScreenStreamManager(applicationContext, currentDisplay, windowManager)
//        screenStreamManager.onStop(this::stop)
        screenStreamManager.onIceCandidate {
            webSocket.send("onIceCandidate|" + gson.toJson(it.toIceCandidateData()))
        }
    }

    fun stop() {
        if (!running) return
        running = false
        if (this::webSocket.isInitialized) {
            webSocket.close(1000, "normal")
        }
        if (this::screenStreamManager.isInitialized) {
            screenStreamManager.release()
        }
        stopSelf()
    }

    fun setWebSocketUrl(url: String) {
        websocketUrl = url
    }

    fun connectWebSocket() {
        launch(Dispatchers.IO) {
            val requestBuilder = Request.Builder()
            requestBuilder.url(websocketUrl)
            webSocket = okHttpClient.newWebSocket(requestBuilder.build(), SignalingWebSocketListener())
        }
    }

    fun prepareStream() {
        Log.d("SERVICE", "startStream intent is null:${mediaProjectionIntent == null}")
        mediaProjectionIntent?.let { intent ->
            screenStreamManager.prepareMedia(intent)
        }
    }

    fun releaseStream() {
        Log.d("SERVICE", "releaseStream")
        if (screenStreamManager.isPrepared) {
            screenStreamManager.release()
        }
    }

    private fun createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d("SERVICE", "创建通知")
            updateForegroundNotification(R.string.service_name)
        }
    }

    /**
     * Android O及以上创建前台通知
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun updateForegroundNotification(message: Int) {
        val mNotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mNotificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_ID,
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        startForeground(
            1, Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.baseline_stream_24)
                .setContentText(getString(message))
                .setContentIntent(mConfigureIntent)
                .build()
        )
    }

    private inner class SignalingWebSocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            isWebSocketConnected = true
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!text.contains("|")) return
            val command = text.substringBefore("|")
            val clientKey = text.substring(command.length + 1).substringBefore("|")
            val dataString = text.substring(command.length + clientKey.length + 2).substringAfter("|")
            Log.d("SERVICE", "command:$command clientKey:$clientKey")
            when (command) {
                "receiver-online" -> {
                    if (screenStreamManager.peerConnectionSize() == 0 || !screenStreamManager.isPrepared) {
                        prepareStream()
                    }
                    if (!screenStreamManager.isPrepared) {
                        Log.e("SERVICE", "ScreenStreamManager无法准备串流内容")
                        return
                    }
                    screenStreamManager.createPeerConnection(key = clientKey, object : SimpleSessionDescriptionObserver() {
                        override fun onCreateSuccess(sdp: SessionDescription) {
                            super.onCreateSuccess(sdp)
                            webSocket.send("offer|$clientKey|${gson.toJson(sdp.toSessionDescriptionData())}")
                        }
                    }, object : SimplePeerConnectionObserver() {
                        override fun onIceCandidate(candidate: IceCandidate) {
                            super.onIceCandidate(candidate)
                            webSocket.send("onIceCandidate|$clientKey|${gson.toJson(candidate.toIceCandidateData())}")
                        }
                    })
                }

                "receiver-offline" -> {
                    screenStreamManager.deposePeerConnection(clientKey)
                    if (screenStreamManager.peerConnectionSize() == 0) {
                        releaseStream()
                    }
                }

                "answer" -> {
                    val sdp = gson.fromJson(dataString, SessionDescriptionData::class.java).toSessionDescription()
                    screenStreamManager.getPeerConnection(clientKey)?.setRemoteDescription(SimpleSessionDescriptionObserver(), sdp)
                }

                "onIceCandidate" -> {
                    val iceCandidate = gson.fromJson(dataString, IceCandidateData::class.java).toIceCandidate()
                    screenStreamManager.getPeerConnection(clientKey)?.addIceCandidate(iceCandidate)
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("SERVICE", "websocket onClosing")
            isWebSocketConnected = false
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.d("SERVICE", "websocket onFailure")
            t.printStackTrace()
            isWebSocketConnected = false
            stopStreamAndRetry()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("SERVICE", "websocket onClosed:$code $reason")
            isWebSocketConnected = false
            stopStreamAndRetry()
        }

        fun stopStreamAndRetry() {
            releaseStream()
            if (!running) return
            launch {
                delay(1000)
                connectWebSocket()
            }
        }

    }

    companion object {
        /**
         * 通知频道Id
         */
        const val NOTIFICATION_CHANNEL_ID = "ConnectionService"
    }
}

class StreamServiceBinder(val streamService: StreamService) : Binder()


class StreamServiceConnection : ServiceConnection {
    var isConnected by mutableStateOf(false)
    lateinit var streamService: StreamService
    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        Log.d("SERVICE", "onServiceConnected")
        val binder = service as StreamServiceBinder
        streamService = binder.streamService
        isConnected = true
        if (!streamService.running) {
            streamService.start()
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        Log.d("SERVICE", "onServiceDisconnected")
        isConnected = false
    }

}

data class SessionDescriptionData(val type: String, val sdp: String) {
    fun toSessionDescription(): SessionDescription {
        return SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)
    }
}

fun SessionDescription.toSessionDescriptionData(): SessionDescriptionData {
    return SessionDescriptionData(type.name.lowercase(), description)
}

data class IceCandidateData(
    val candidate: String,
    val sdpMLineIndex: Int,
    val sdpMid: String,
    val usernameFragment: String? = null
) {
    fun toIceCandidate(): IceCandidate {
        return IceCandidate(sdpMid, sdpMLineIndex, candidate)
    }
}

fun IceCandidate.toIceCandidateData(): IceCandidateData {
    val usernameFragment = sdp.substringAfter(" ufrag ").substringBefore(" ")
    return IceCandidateData(sdp, sdpMLineIndex, sdpMid, usernameFragment)
}