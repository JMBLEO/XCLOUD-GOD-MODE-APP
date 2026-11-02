package com.xcloud.godmode.network

import android.util.Log
import okhttp3.*

object RtaWebSocketClient {
    private const val TAG = "RtaWebSocketClient"

    fun listenToXboxPartyInvites(client: OkHttpClient, xstsToken: String, onInviteReceived: () -> Unit): WebSocket {
        val request = Request.Builder()
            .url("wss://rta.xboxlive.com/connect")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val authMessage = """[1, 1, {"Authorization":"XBL3.0 x=-;$xstsToken"}]"""
                webSocket.send(authMessage)
                
                val subscribeMessage = """[2, 2, {"uri":"https://sessiondirectory.xboxlive.com/connections/"}]"""
                webSocket.send(subscribeMessage)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("PartyInvite")) {
                    Log.d(TAG, "Party invite received in real time via OkHttp WebSocket")
                    onInviteReceived()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "RTA WebSocket error: ${t.message}")
            }
        }

        return client.newWebSocket(request, listener)
    }
}
