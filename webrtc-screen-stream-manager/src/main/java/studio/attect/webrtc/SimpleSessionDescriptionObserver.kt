package studio.attect.webrtc

import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

open class SimpleSessionDescriptionObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {

    }

    override fun onSetSuccess() {

    }

    override fun onCreateFailure(error: String?) {

    }

    override fun onSetFailure(error: String?) {

    }

}