package studio.attect.webrtc.screen

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import studio.attect.webrtc.screen.ui.theme.ScreenStreamWebRtcTheme

class MainActivity : ComponentActivity() {
    /**
     * 信令服务器url地址
     */
    private val serverUrl = "ws://192.168.8.100:9999/ws/sender"

    private var mediaProjectionIntent: Intent? by mutableStateOf(null)

    private val mediaProjectionManager: MediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    private val mediaPermissionLauncher = registerForActivityResult(MediaProjectionActivityResultContract()) { mediaProjectionIntent ->
        Log.d("PERM", "获取屏幕权限：${mediaProjectionIntent != null}")
        this.mediaProjectionIntent = mediaProjectionIntent
        if (streamServiceConnection.isConnected) {
            streamServiceConnection.streamService.mediaProjectionIntent = mediaProjectionIntent
        }
        if (mediaProjectionIntent == null) {
            Toast.makeText(this, getString(R.string.no_screen_capture_permission), Toast.LENGTH_LONG).show()
        }
    }

    private val streamServiceConnection = StreamServiceConnection()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 0)
        setContent {
            ScreenStreamWebRtcTheme {
                // A surface container using the 'background' color from the theme
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column {
                        Button(onClick = {
                            if (!streamServiceConnection.isConnected) {
                                val serviceIntent = Intent(this@MainActivity, StreamService::class.java)
                                ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                                bindService(serviceIntent, streamServiceConnection, Context.BIND_AUTO_CREATE)
                            }
                        }, enabled = !streamServiceConnection.isConnected) {
                            Text(text = "启动后台服务")
                        }
                        Button(onClick = {
                            if (mediaProjectionIntent == null) {
                                mediaPermissionLauncher.launch(mediaProjectionManager)
                            }
                        }, enabled = mediaProjectionIntent == null && streamServiceConnection.isConnected) {
                            Text(text = "申请捕获权限")
                        }
                        Button(onClick = {
                            if (streamServiceConnection.isConnected && !streamServiceConnection.streamService.isWebSocketConnected) {
                                streamServiceConnection.streamService.apply {
                                    setWebSocketUrl(serverUrl)
                                    connectWebSocket()
                                }
                            }
                        }, enabled = streamServiceConnection.isConnected && !streamServiceConnection.streamService.isWebSocketConnected) {
                            Text(text = "连接信令服务器")
                        }

                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (streamServiceConnection.isConnected) {
            unbindService(streamServiceConnection)
        }
    }

}

class MediaProjectionActivityResultContract : ActivityResultContract<MediaProjectionManager, Intent?>() {
    override fun createIntent(context: Context, input: MediaProjectionManager): Intent {
        return input.createScreenCaptureIntent()
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Intent? {
        if (resultCode != Activity.RESULT_OK) return null
        return intent
    }

}