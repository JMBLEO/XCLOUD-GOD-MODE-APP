package com.xcloud.godmode.network

import android.os.StrictMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Response

object NetworkThreadGuard {

    /**
     * Escudo de seguridad en el arranque de MainActivity / Application:
     * Permite que si una librería o un callback del sistema realiza
     * operaciones de red, StrictMode NO mate ni aborte el hilo principal de UI.
     */
    fun installStrictModeNetworkGuard() {
        val policy = StrictMode.ThreadPolicy.Builder()
            .permitAll()
            .build()
        StrictMode.setThreadPolicy(policy)
    }

    /**
     * Extensión obligatoria para OkHttp:
     * Garantiza que NINGUNA llamada .execute() de OkHttp corra jamás en Dispatchers.Main.
     */
    suspend inline fun <T> Call.executeOnIo(
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        crossinline block: (Response) -> T
    ): T = withContext(dispatcher) {
        this@executeOnIo.execute().use { response ->
            block(response)
        }
    }
}
