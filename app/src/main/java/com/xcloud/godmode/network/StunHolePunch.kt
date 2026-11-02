package com.xcloud.godmode.network

/**
 * StunHolePunch: Handles UDP hole punching and STUN binding requests for remote play (xhome).
 */
object StunHolePunch {
    init {
        System.loadLibrary("godmode_native")
    }

    external fun nativeSendStunBindingRequest(host: String, port: Int): Boolean

    fun sendBindingRequest(host: String, port: Int): Boolean {
        return try {
            nativeSendStunBindingRequest(host, port)
        } catch (e: Exception) {
            false
        }
    }
}
