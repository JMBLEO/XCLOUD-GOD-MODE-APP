package com.aistudio.xcloudgodmode.ai.services

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * NetworkClient handles authenticated requests to Xbox Live and GSSV APIs.
 * It automatically injects the required "Signature" header for XAL compliance.
 */
class NetworkClient(private val okHttpClient: OkHttpClient = OkHttpClient()) {

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    fun getPublicKeyJwk(): JSONObject = JoseCripto.getPublicJwk()

    /**
     * Executes a POST request with XAL signature.
     */
    fun post(
        url: String,
        body: JSONObject,
        authToken: String = "",
        headers: Map<String, String> = emptyMap()
    ): Response {
        val bodyString = body.toString()
        val signature = JoseCripto.generateSignatureHeader("POST", url, bodyString, authToken)

        val requestBuilder = Request.Builder()
            .url(url)
            .post(bodyString.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Signature", signature)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .addHeader("x-xbl-contract-version", "1")

        if (authToken.isNotEmpty()) {
            requestBuilder.addHeader("Authorization", authToken)
        }

        headers.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }

        return okHttpClient.newCall(requestBuilder.build()).execute()
    }

    /**
     * Executes a GET request with XAL signature.
     */
    fun get(
        url: String,
        authToken: String = "",
        headers: Map<String, String> = emptyMap()
    ): Response {
        val signature = JoseCripto.generateSignatureHeader("GET", url, "", authToken)

        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .addHeader("Signature", signature)
            .addHeader("Accept", "application/json")
            .addHeader("x-xbl-contract-version", "1")

        if (authToken.isNotEmpty()) {
            requestBuilder.addHeader("Authorization", authToken)
        }

        headers.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }

        return okHttpClient.newCall(requestBuilder.build()).execute()
    }
}
