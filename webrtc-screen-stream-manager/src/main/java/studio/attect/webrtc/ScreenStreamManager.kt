package studio.attect.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import kotlin.coroutines.CoroutineContext

class ScreenStreamManager(val context: Context, val display: Display, val windowManager: WindowManager) : CoroutineScope {

    private val job = Job()

    override val coroutineContext: CoroutineContext = Dispatchers.Default + job + CoroutineName("ScreenStreamManager")

    /**
     * 是否已经开始
     */
    var isStarted = false
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

    /**
     * 提供EGL的渲染上下文及EGL的版本兼容
     */
    private lateinit var eglBase: EglBase

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
     * 点对点连接
     */
    private var peerConnection: PeerConnection? = null

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

    /**
     * 开始
     *
     * 需要提供来自[MediaProjectionManager.createScreenCaptureIntent]的[mediaProjectionIntent]
     */
    fun start(mediaProjectionIntent: Intent, sdpHandler: (sessionDescription: SessionDescription) -> Unit) {
        if (!this::onLocalIceCandidateHandler.isInitialized) {
            throw IllegalStateException("请先设置onIceCandidate")
        }
        if (isStarted) throw IllegalStateException("已经开始")
        isStarted = true

        videoCapture = ScreenCapturerAndroid(mediaProjectionIntent, object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                if (!isScreenSizeChangeStop) {
                    stop()
                }
            }
        })
        eglBase = EglBase.create()


        val videoEncoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val videoDecoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoEncoderFactory(videoEncoderFactory)
            .setVideoDecoderFactory(videoDecoderFactory)
            .createPeerConnectionFactory()

        val surfaceTextureHelper = SurfaceTextureHelper.create("ScreenStreamManager", eglBase.eglBaseContext)
        val videoSource = peerConnectionFactory.createVideoSource(videoCapture.isScreencast)

        videoCapture.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)

        val videoTrack = peerConnectionFactory.createVideoTrack("screen", videoSource)
        val currentDisplayMetrics = displayMetrics

        videoCapture.startCapture(currentDisplayMetrics.widthPixels, currentDisplayMetrics.heightPixels, 60)

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
                    videoCapture.startCapture(currentWidth, currentHeight, 60)
                }
            }
        }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnectionObserver() {
            override fun onIceCandidate(candidate: IceCandidate) {
                if (this@ScreenStreamManager::onLocalIceCandidateHandler.isInitialized) {
                    onLocalIceCandidateHandler.invoke(candidate)
                }
            }
        })

        mediaStream = peerConnectionFactory.createLocalMediaStream("device")
        mediaStream.addTrack(videoTrack)
        peerConnection?.addStream(mediaStream)

        val mediaConstraints = MediaConstraints()
        mediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))

        peerConnection?.createOffer(object : SessionDescriptionObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(SessionDescriptionObserver(), sdp)
                sdpHandler.invoke(sdp)
            }
        }, mediaConstraints)
    }

    /**
     * 停止
     * 必须先开始，才能停止
     */
    fun stop() {
        if (!isStarted) throw IllegalStateException("没有开始")

        runCatching {
            peerConnection?.dispose()
        }.exceptionOrNull()?.printStackTrace()

        runCatching {
            videoCapture.stopCapture()
            videoCapture.dispose()
        }.exceptionOrNull()?.printStackTrace()

        runCatching {
            eglBase.release()
        }.exceptionOrNull()?.printStackTrace()

        runCatching {
            peerConnectionFactory.dispose()
        }.exceptionOrNull()?.printStackTrace()

        isStarted = false
        if (this::onStopHandler.isInitialized) {
            onStopHandler.invoke()
        }
    }

    /**
     * 本地连接候选
     */
    fun onIceCandidate(handler: (iceCandidate: IceCandidate) -> Unit) {
        onLocalIceCandidateHandler = handler
    }

    /**
     * 设置远端回复
     */
    fun setRemoteAnswer(sdp: SessionDescription) {
        if (!isStarted) throw IllegalStateException("没有start")
        peerConnection?.setRemoteDescription(SessionDescriptionObserver(), sdp)
    }

    /**
     * 添加远端连接候选
     */
    fun addRemoteIceCandidate(iceCandidate: IceCandidate) {
        if (!isStarted) throw IllegalStateException("没有start")
        peerConnection?.addIceCandidate(iceCandidate)
    }

    fun onStop(block: () -> Unit) {
        onStopHandler = block
    }

    private open class PeerConnectionObserver : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState) {

        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {

        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {

        }

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {

        }

        override fun onIceCandidate(candidate: IceCandidate) {

        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {

        }

        override fun onAddStream(stream: MediaStream?) {

        }

        override fun onRemoveStream(stream: MediaStream?) {

        }

        override fun onDataChannel(dataChannel: DataChannel?) {

        }

        override fun onRenegotiationNeeded() {

        }

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {

        }

    }

    private open class SessionDescriptionObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {

        }

        override fun onSetSuccess() {

        }

        override fun onCreateFailure(error: String?) {

        }

        override fun onSetFailure(error: String?) {

        }

    }
}