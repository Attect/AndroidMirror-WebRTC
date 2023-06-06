package com.example.data

data class IceCandidateData(
    val candidate: String,
    val sdpMLineIndex: Int,
    val sdpMid: String,
    val usernameFragment: String? = null
) {

    val id: String
        get() {
            return candidate.substringAfter("candidate:").substringBefore(" ")
        }
}