package com.xcloud.godmode.network

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * NetworkClient handles authenticated requests to Xbox Live and GSSV APIs.
 * It automatically injects the required "Signature" header for XAL compliance.
 * Now supports DNS over HTTPS (DoH) for bypass and strict network environments.
 */
class NetworkClient(
    enableDoh: Boolean = true,
    private var okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {

    init {
        if (enableDoh) {
            val appCache = okHttpClient.cache
            val bootstrapClient = okHttpClient.newBuilder().build()
            val dns = DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
                .bootstrapDnsHosts(listOf(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1")))
                .build()
            
            okHttpClient = okHttpClient.newBuilder()
                .dns(dns)
                .build()
        }
    }

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

    val httpClient: OkHttpClient get() = okHttpClient
}
