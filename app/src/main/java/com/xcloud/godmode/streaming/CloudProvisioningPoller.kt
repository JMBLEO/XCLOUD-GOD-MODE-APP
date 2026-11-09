package com.xcloud.godmode.streaming

import com.xcloud.godmode.engine.CloudLaunchException
import com.xcloud.godmode.engine.LaunchAttemptId
import com.xcloud.godmode.engine.StartupGate
import com.xcloud.godmode.engine.StreamStartupStateMachine
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

enum class RemoteProvisioningState {
    WAITING_FOR_RESOURCES,
    PROVISIONING,
    READY_TO_CONNECT,
    FAILED,
    CANCELED
}

data class ProvisioningPollResult(
    val state: RemoteProvisioningState,
    val estimatedQueueSeconds: Int = 0,
    val errorDetails: String? = null
)

interface SessionStatePollingHttpClient {
    suspend fun getSessionStateJson(url: String, bearerToken: CharArray): Pair<Int, String>
}

class CloudProvisioningPoller(
    private val httpClient: SessionStatePollingHttpClient
) {
    suspend fun awaitReadyToConnect(
        attemptId: LaunchAttemptId,
        baseRegionUri: String,
        sessionPath: String,
        gsToken: CharArray,
        startupGates: StreamStartupStateMachine,
        onQueueProgress: (ProvisioningPollResult) -> Unit
    ) {
        val stateUrl = "${baseRegionUri.trimEnd('/')}/${sessionPath.trimStart('/')}/state"
        var polls = 0
        val maxPolls = 180

        while (polls < maxPolls) {
            coroutineContext.ensureActive()
            val (httpCode, body) = httpClient.getSessionStateJson(stateUrl, gsToken)
            if (httpCode !in 200..299) {
                throw CloudLaunchException.ProtocolFailure(
                    statusCode = httpCode,
                    responseCode = "PROVISIONING_HTTP_$httpCode",
                    safeMessage = "Error al consultar el estado del blade en Azure",
                    correlationId = null
                )
            }

            val json = JSONObject(body)
            val rawState = json.optString("state", "")
            val queueSeconds = json.optInt("estimatedAllocationTimeInSeconds", 0)
            val errorMsg = json.optJSONObject("errorDetails")?.optString("message")

            when (rawState) {
                "WaitingForResources" -> {
                    onQueueProgress(
                        ProvisioningPollResult(RemoteProvisioningState.WAITING_FOR_RESOURCES, queueSeconds)
                    )
                }
                "Provisioning" -> {
                    onQueueProgress(
                        ProvisioningPollResult(RemoteProvisioningState.PROVISIONING, queueSeconds)
                    )
                }
                "ReadyToConnect" -> {
                    onQueueProgress(ProvisioningPollResult(RemoteProvisioningState.READY_TO_CONNECT, 0))
                    startupGates.complete(attemptId, StartupGate.SESSION_PROVISIONED)
                    return
                }
                "Failed", "Canceled" -> {
                    throw CloudLaunchException.ProtocolFailure(
                        statusCode = 500,
                        responseCode = rawState.uppercase(),
                        safeMessage = errorMsg ?: "El aprovisionamiento de la consola remota falló ($rawState)",
                        correlationId = null
                    )
                }
            }

            polls++
            delay(1_000L)
        }

        throw CloudLaunchException.ProtocolFailure(
            statusCode = 408,
            responseCode = "PROVISIONING_TIMEOUT",
            safeMessage = "Tiempo de espera agotado aguardando asignación de consola en Azure.",
            correlationId = null
        )
    }
}
