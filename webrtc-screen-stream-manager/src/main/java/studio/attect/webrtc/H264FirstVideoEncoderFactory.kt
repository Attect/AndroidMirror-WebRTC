package studio.attect.webrtc

import android.util.Log
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase.Context
import org.webrtc.VideoCodecInfo

class H264FirstVideoEncoderFactory(eglContext: Context, enableIntelVp8Encoder: Boolean, enableH264HighProfile: Boolean) : DefaultVideoEncoderFactory(eglContext, enableIntelVp8Encoder, enableH264HighProfile) {
    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        val encoders = super.getSupportedCodecs().reversed().toTypedArray()
        encoders.forEach {
            Log.d("Encoder", "name:${it.name}")
        }
        return encoders
    }
}