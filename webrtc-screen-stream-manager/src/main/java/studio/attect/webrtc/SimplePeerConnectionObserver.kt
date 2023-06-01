package studio.attect.webrtc

import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver

open class SimplePeerConnectionObserver : PeerConnection.Observer {
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