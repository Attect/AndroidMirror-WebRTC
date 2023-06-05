package studio.attect.webrtc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoTrack
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule.AudioRecordErrorCallback
import org.webrtc.audio.JavaAudioDeviceModule.AudioRecordStateCallback
import org.webrtc.audio.JavaAudioDeviceModule.AudioTrackErrorCallback
import org.webrtc.audio.JavaAudioDeviceModule.AudioTrackStateCallback
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

class ScreenStreamManager(val context: Context, val display: Display, val windowManager: WindowManager) : CoroutineScope {

    private val job = Job()

    override val coroutineContext: CoroutineContext = Dispatchers.Default + job + CoroutineName("ScreenStreamManager")

    /**
     * 已经就绪
     */
    var isPrepared = false
        private set

    /**
     * 屏幕参数获取对象（缓存对象）
     */
    private val displayMetricsBuffer = DisplayMetrics().also { display.getRealMetrics(it) }

    /**
     * 获取给定屏幕的显示参数
     */
    private val displayMetrics: DisplayMetrics
        get() {
            display.getRealMetrics(displayMetricsBuffer)
            return displayMetricsBuffer
        }

    /**
     * 屏幕捕获
     */
    private lateinit var videoCapture: ScreenCapturerAndroid

    private lateinit var mediaStream: MediaStream
    private lateinit var videoTrack: VideoTrack

    /**
     * 提供EGL的渲染上下文及EGL的版本兼容
     */
    private lateinit var eglBase: EglBase

    /**
     * 音频设备模块
     */
    private lateinit var audioDeviceModule: AudioDeviceModule
    private lateinit var audioTrack: AudioTrack

    /**
     * 点对点连接工厂
     */
    private lateinit var peerConnectionFactory: PeerConnectionFactory

    /**
     * 点对点连接配置
     */
    private val rtcConfig by lazy {
        PeerConnection.RTCConfiguration(arrayListOf()).apply {
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
        }
    }

    /**
     * 点对点连接Map
     */
    private var peerConnectionMap: ConcurrentHashMap<String, PeerConnection> = ConcurrentHashMap()

    /**
     * 观察屏幕旋转的协程工作
     */
    private var watchScreenRotationJob: Job? = null

    /**
     * 本地IceCandidate处理
     */
    private lateinit var onLocalIceCandidateHandler: (IceCandidate) -> Unit

    /**
     * 是否因为屏幕旋转而停止的屏幕捕获
     */
    private var isScreenSizeChangeStop = false

    /**
     * 退出回调
     */
    private lateinit var onStopHandler: () -> Unit

    private val mediaProjectionManager by lazy {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private var mediaProjection: MediaProjection? = null

    /**
     * 准备推流媒体
     *
     * 需要提供来自[MediaProjectionManager.createScreenCaptureIntent]的[mediaProjectionIntent]
     */
    fun prepareMedia(mediaProjectionIntent: Intent) {
        if (!this::onLocalIceCandidateHandler.isInitialized) {
            throw IllegalStateException("请先设置onIceCandidate")
        }
        if (isPrepared) throw IllegalStateException("已经就绪")
        isPrepared = true
        val currentMediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, mediaProjectionIntent)
        mediaProjection = currentMediaProjection

        videoCapture = ScreenCapturerAndroid(currentMediaProjection, object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                if (!isScreenSizeChangeStop) {
                    release()
                }
            }
        })
        eglBase = EglBase.create()


        val videoEncoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, false)
        val videoDecoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        audioDeviceModule = createJavaAudioDevice()
        audioDeviceModule.setMediaProjection(currentMediaProjection)

        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoEncoderFactory(videoEncoderFactory)
            .setVideoDecoderFactory(videoDecoderFactory)
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()

        val surfaceTextureHelper = SurfaceTextureHelper.create("ScreenStreamManager", eglBase.eglBaseContext)
        val videoSource = peerConnectionFactory.createVideoSource(videoCapture.isScreencast)
//        videoSource.adaptOutputFormat(960,540,540,960,60)

        videoCapture.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)

        videoTrack = peerConnectionFactory.createVideoTrack("screen", videoSource)
        val currentDisplayMetrics = displayMetrics

        val scale = 1
        val cWidth = (currentDisplayMetrics.widthPixels * scale).toInt()
        val cHeight = (currentDisplayMetrics.heightPixels*scale).toInt()
        videoCapture.startCapture(cWidth,cHeight , 15)
        Log.d("DEBUG","cWdith:$cWidth cHeight:$cHeight")

        watchScreenRotationJob = launch {
            var currentWidth = currentDisplayMetrics.widthPixels
            var currentHeight = currentDisplayMetrics.heightPixels
            while (isActive) {
                val nowDisplayMetrics = displayMetrics
                if (nowDisplayMetrics.widthPixels != currentWidth || nowDisplayMetrics.heightPixels != currentHeight) {
                    currentWidth = nowDisplayMetrics.widthPixels
                    currentHeight = nowDisplayMetrics.heightPixels
                    try {
                        isScreenSizeChangeStop = true
                        videoCapture.stopCapture()
                        isScreenSizeChangeStop = false
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    videoCapture.startCapture((currentWidth * scale).toInt(), (currentHeight * scale).toInt(), 15)
                }
                delay(500)
            }
        }

        val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
        })
        audioTrack = peerConnectionFactory.createAudioTrack("deviceSound", audioSource)
        audioTrack.setEnabled(true)


    }

    /**
     * 停止
     * 必须先开始，才能停止
     */
    fun release() {
        if (!isPrepared) throw IllegalStateException("没有就绪")
        isPrepared = false

        peerConnectionMap.keys().iterator().forEach { key ->
            runCatching {
                peerConnectionMap[key]?.dispose()
            }.exceptionOrNull()?.printStackTrace()
        }
        peerConnectionMap.clear()

        runCatching {
            videoCapture.stopCapture()
            videoCapture.dispose()
        }.exceptionOrNull()?.printStackTrace()

        runCatching {
            eglBase.release()
        }.exceptionOrNull()?.printStackTrace()

        runCatching {
            audioDeviceModule.release()
        }.exceptionOrNull()?.printStackTrace()

        runCatching {
            peerConnectionFactory.dispose()
        }.exceptionOrNull()?.printStackTrace()

        mediaProjection?.stop()
        mediaProjection = null

        if (this::onStopHandler.isInitialized) {
            onStopHandler.invoke()
        }
    }

    fun createPeerConnection(key: String, sdpObserver: SimpleSessionDescriptionObserver, peerConnectionObserver: SimplePeerConnectionObserver) {
        if (!isPrepared) throw IllegalStateException("媒体没有准备好，请先调用prepareMedia")
        if (peerConnectionMap.contains(key)) {
            peerConnectionMap[key]?.dispose()
        }
        val mediaConstraints = MediaConstraints()
        mediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))

        val peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, peerConnectionObserver) ?: throw RuntimeException("创建PeerConnection失败")

//        val mediaStream = peerConnectionFactory.createLocalMediaStream("receiver-$key")
//        mediaStream.addTrack(videoTrack)
        peerConnection.addTrack(videoTrack)
        peerConnection.addTrack(audioTrack)
        peerConnection.setAudioRecording(true)
        peerConnection.setBitrate(1024 * 1024 * 512, 1024 * 1024 * 1024, Integer.MAX_VALUE)

        peerConnection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection.setLocalDescription(SimpleSessionDescriptionObserver(), sdp)
                sdpObserver.onCreateSuccess(sdp)
            }

            override fun onSetSuccess() {
                sdpObserver.onSetSuccess()
            }

            override fun onCreateFailure(error: String?) {
                sdpObserver.onCreateFailure(error)
            }

            override fun onSetFailure(error: String?) {
                sdpObserver.onSetFailure(error)
            }

        }, mediaConstraints)

        peerConnectionMap[key] = peerConnection
    }

    fun getPeerConnection(key: String): PeerConnection? {
        if (!isPrepared) throw IllegalStateException("媒体没有准备好，请先调用prepareMedia")
        return peerConnectionMap[key]
    }

    fun deposePeerConnection(key: String) {
        peerConnectionMap[key]?.close()
        peerConnectionMap.remove(key)
    }

    fun peerConnectionSize(): Int {
        return peerConnectionMap.size
    }

    /**
     * 本地连接候选
     */
    fun onIceCandidate(handler: (iceCandidate: IceCandidate) -> Unit) {
        onLocalIceCandidateHandler = handler
    }

    fun onStop(block: () -> Unit) {
        onStopHandler = block
    }

    private fun createJavaAudioDevice(): AudioDeviceModule {
        val audioRecordErrorCallback = object : AudioRecordErrorCallback {
            override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
                Log.d("DEBUG", "onWebRtcAudioRecordInitError:$errorMessage")
            }

            override fun onWebRtcAudioRecordStartError(errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?, errorMessage: String?) {
                Log.d("DEBUG", "onWebRtcAudioRecordStartError errorCode:$errorCode message:$errorMessage")
            }

            override fun onWebRtcAudioRecordError(errorMessage: String?) {
                Log.d("DEBUG", "onWebRtcAudioRecordError:$errorMessage")
            }
        }

        val audioTrackErrorCallback = object : AudioTrackErrorCallback {
            override fun onWebRtcAudioTrackInitError(errorMessage: String?) {
                Log.d("DEBUG", "onWebRtcAudioTrackInitError:$errorMessage")
            }

            override fun onWebRtcAudioTrackStartError(errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode?, errorMessage: String?) {
                Log.d("DEBUG", "onWebRtcAudioTrackStartError errorCode:$errorCode message:$errorMessage")
            }

            override fun onWebRtcAudioTrackError(errorMessage: String?) {
                Log.d("DEBUG", "onWebRtcAudioTrackError:$errorMessage")
            }
        }

        val audioRecordStateCallback = object : AudioRecordStateCallback {
            override fun onWebRtcAudioRecordStart() {
                Log.d("DEBUG", "开始录制音频")
            }

            override fun onWebRtcAudioRecordStop() {
                Log.d("DEBUG", "停止录制音频")
            }
        }

        val audioRecordTrackStateCallback = object : AudioTrackStateCallback {
            override fun onWebRtcAudioTrackStart() {
                Log.d("DEBUG", "音频轨道开始")
            }

            override fun onWebRtcAudioTrackStop() {
                Log.d("DEBUG", "音频轨道结束")
            }

        }

        return JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioRecordErrorCallback(audioRecordErrorCallback)
            .setAudioTrackErrorCallback(audioTrackErrorCallback)
            .setAudioRecordStateCallback(audioRecordStateCallback)
            .setAudioTrackStateCallback(audioRecordTrackStateCallback)
            .createAudioDeviceModule()
    }


}