package com.xcloud.godmode.viewmodels

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloud.godmode.network.CompleteXboxSession
import com.xcloud.godmode.network.MsaQrAndSisuAuthEngine
import com.xcloud.godmode.network.SisuBrowserRedirectTicket
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface QrAndOneClickUiState {
    data object Idle : QrAndOneClickUiState
    data object LoadingTicket : QrAndOneClickUiState
    data object WaitingBrowserCallback : QrAndOneClickUiState
    data class MsaQrActive(
        val userCode: String,
        val verificationUri: String,
        val remainingSeconds: Int
    ) : QrAndOneClickUiState
    data object ExchangingSessionTokens : QrAndOneClickUiState
    data class Authenticated(val session: CompleteXboxSession) : QrAndOneClickUiState
    data class Error(val message: String) : QrAndOneClickUiState
}

class XboxAuthModalViewModel(
    private val authEngine: MsaQrAndSisuAuthEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow<QrAndOneClickUiState>(QrAndOneClickUiState.Idle)
    val uiState: StateFlow<QrAndOneClickUiState> = _uiState.asStateFlow()

    private var qrPollingJob: Job? = null

    @Volatile
    private var pendingSisuTicket: SisuBrowserRedirectTicket? = null

    init {
        viewModelScope.launch {
            com.xcloud.godmode.GlobalAuthCapture.codes.collect { code ->
                onDeepLinkCodeCaptured(code)
            }
        }
    }

    /**
     * Inicia el flujo MSA QR Login al abrir la pestaña "Vincular QR / TV".
     */
    fun startMsaQrLoginFlow(spoofedIp: String? = null) {
        qrPollingJob?.cancel()
        qrPollingJob = viewModelScope.launch {
            _uiState.value = QrAndOneClickUiState.LoadingTicket
            try {
                val ticket = authEngine.requestMsaQrCodeTicket()
                pendingSisuTicket = ticket.sisuTicket
                
                _uiState.value = QrAndOneClickUiState.MsaQrActive(
                    userCode = ticket.userCode,
                    verificationUri = ticket.verificationUri,
                    remainingSeconds = ticket.expiresInSeconds
                )

                val session = authEngine.pollMsaQrTicketUntilApproved(
                    ticket = ticket,
                    spoofedForwardedForIp = spoofedIp,
                    onTickRemainingSeconds = { remaining ->
                        _uiState.value = QrAndOneClickUiState.MsaQrActive(
                            userCode = ticket.userCode,
                            verificationUri = ticket.verificationUri,
                            remainingSeconds = remaining
                        )
                    }
                )
                _uiState.value = QrAndOneClickUiState.Authenticated(session)
            } catch (e: Exception) {
                _uiState.value = QrAndOneClickUiState.Error(
                    e.message ?: "No se pudo generar el código QR de Microsoft."
                )
            }
        }
    }

    /**
     * Inicia el flujo SISU de 1 clic en la pestaña "Inicio Rápido".
     */
    fun startOneClickSisuLogin(activityContext: Context) {
        qrPollingJob?.cancel()
        viewModelScope.launch {
            _uiState.value = QrAndOneClickUiState.LoadingTicket
            try {
                val ticket = authEngine.prepareSisuOneClickTicket()
                pendingSisuTicket = ticket
                _uiState.value = QrAndOneClickUiState.WaitingBrowserCallback

                val uri = Uri.parse(ticket.msaOauthRedirectUrl)
                try {
                    val customTabs = CustomTabsIntent.Builder()
                        .setShowTitle(true)
                        .build()
                    customTabs.intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                    customTabs.launchUrl(activityContext, uri)
                } catch (e: Exception) {
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
                    }
                    activityContext.startActivity(intent)
                }
            } catch (e: Exception) {
                _uiState.value = QrAndOneClickUiState.Error(
                    e.message ?: "Error al abrir el inicio de sesión rápido."
                )
            }
        }
    }

    /**
     * Invocado automáticamente desde MainActivity.onNewIntent() al regresar de ms-xal://
     */
    fun onDeepLinkCodeCaptured(code: String, spoofedIp: String? = null) {
        val ticket = pendingSisuTicket ?: return
        viewModelScope.launch {
            _uiState.value = QrAndOneClickUiState.ExchangingSessionTokens
            try {
                val session = authEngine.completeSisuOneClickExchange(
                    authorizationCode = code,
                    ticket = ticket,
                    spoofedForwardedForIp = spoofedIp
                )
                _uiState.value = QrAndOneClickUiState.Authenticated(session)
            } catch (e: Exception) {
                _uiState.value = QrAndOneClickUiState.Error(
                    "Error al verificar tu cuenta de Xbox: ${e.message}"
                )
            }
        }
    }

    fun resetState() {
        qrPollingJob?.cancel()
        _uiState.value = QrAndOneClickUiState.Idle
    }
}
