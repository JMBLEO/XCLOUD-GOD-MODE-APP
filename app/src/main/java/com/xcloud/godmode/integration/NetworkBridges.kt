package com.xcloud.godmode.integration

import com.xcloud.godmode.catalog.OfferingId
import com.xcloud.godmode.network.*
import com.xcloud.godmode.session.AuthSessionPort
import com.xcloud.godmode.session.SessionHttpClient
import com.xcloud.godmode.session.SessionHttpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Implementation of SessionHttpClient using OkHttp, compliant with Carrier-Grade requirements.
 */
class OkHttpSessionClient(private val okHttpClient: OkHttpClient) : SessionHttpClient {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun postJson(
        url: String,
        bearerToken: CharArray,
        body: String,
        headers: Map<String, String>
    ): SessionHttpResult = withContext(Dispatchers.IO) {
        val reqBuilder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(jsonMediaType))
            .addHeader("Authorization", "Bearer ${String(bearerToken)}")

        headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }

        okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            val correlationId = response.header("X-MS-Correlation-Id") 
                ?: response.header("ms-cv")
                ?: response.header("X-MS-Session-Id")

            SessionHttpResult(
                statusCode = response.code,
                body = responseBody,
                correlationId = correlationId
            )
        }
    }

    override suspend fun getJson(
        url: String,
        bearerToken: CharArray,
        headers: Map<String, String>
    ): SessionHttpResult = withContext(Dispatchers.IO) {
        val reqBuilder = Request.Builder()
            .url(url)
            .get()
            .addHeader("Authorization", "Bearer ${String(bearerToken)}")

        headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }

        okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            val correlationId = response.header("X-MS-Correlation-Id") 
                ?: response.header("ms-cv")

            SessionHttpResult(
                statusCode = response.code,
                body = responseBody,
                correlationId = correlationId
            )
        }
    }
}

/**
 * Bridge between existing XboxFullAuthManager and the new AuthSessionPort.
 */
class CarrierAuthPort(
    private val vault: HardwareTokenVault,
    private val authAuthority: AuthenticationAuthority
) : AuthSessionPort {

    override val activeOfferingId: OfferingId
        get() = OfferingId(vault.loadEnvelope()?.market?.let { if (it == "CL") "xgpuweb" else "xgpuweb" } ?: "xgpuweb")

    override val currentAuthHealthScore: Int
        get() = authAuthority.authHealth.value.overallScore

    override suspend fun requireHealthyEnvelope(): SecureTokenEnvelope {
        val stored = vault.loadEnvelope() ?: throw IllegalStateException("No hay sesión activa en el Vault")
        
        // Convert StoredTokenEnvelope to SecureTokenEnvelope
        return SecureTokenEnvelope(
            msaAccessToken = stored.msaAccessToken.toCharArray(),
            msaRefreshToken = stored.msaRefreshToken.toCharArray(),
            msaExpiresAtElapsedMs = stored.msaExpiresAtElapsedMs,
            lptTransferToken = stored.lptTransferToken.toCharArray(),
            xblUserToken = stored.xblUserToken.toCharArray(),
            userHashUhs = stored.userHashUhs.toCharArray(),
            xstsWebToken = stored.xstsWebToken.toCharArray(),
            xstsGssvToken = stored.xstsGssvToken.toCharArray(),
            xstsExpiresAtElapsedMs = stored.xstsExpiresAtElapsedMs,
            gsStreamToken = stored.gsStreamToken.toCharArray(),
            gsExpiresAtElapsedMs = stored.gsExpiresAtElapsedMs,
            discoveredBaseRegionUri = stored.discoveredBaseRegionUri,
            market = stored.market
        )
    }

    override suspend fun refreshCredentialsOnce(): SecureTokenEnvelope {
        val current = vault.loadEnvelope() ?: throw IllegalStateException("Nada que refrescar")
        val epoch = 0L // Legacy bypass for refresh
        val updated = authAuthority.refreshAllCredentialsSilently(current, epoch)
        
        return SecureTokenEnvelope(
            msaAccessToken = updated.msaAccessToken.toCharArray(),
            msaRefreshToken = updated.msaRefreshToken.toCharArray(),
            msaExpiresAtElapsedMs = updated.msaExpiresAtElapsedMs,
            lptTransferToken = updated.lptTransferToken.toCharArray(),
            xblUserToken = updated.xblUserToken.toCharArray(),
            userHashUhs = updated.userHashUhs.toCharArray(),
            xstsWebToken = updated.xstsWebToken.toCharArray(),
            xstsGssvToken = updated.xstsGssvToken.toCharArray(),
            xstsExpiresAtElapsedMs = updated.xstsExpiresAtElapsedMs,
            gsStreamToken = updated.gsStreamToken.toCharArray(),
            gsExpiresAtElapsedMs = updated.gsExpiresAtElapsedMs,
            discoveredBaseRegionUri = updated.discoveredBaseRegionUri,
            market = updated.market
        )
    }
}
