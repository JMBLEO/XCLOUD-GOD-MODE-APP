package com.xcloud.godmode.network

import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * LanRelayAuthServer: Micro HTTP server running locally on Android TV or Handheld console.
 * Allows user to scan a QR code with their mobile phone or PC on the same LAN,
 * authenticate via Microsoft Live on their phone, and relay the return code back to the TV/Handheld.
 */
class LanRelayAuthServer(
    private val port: Int = 8989,
    private val onAuthCodeReceived: (String) -> Unit
) {
    companion object {
        private const val TAG = "LanRelayAuthServer"
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var actualPort: Int = port

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine local IP: ${e.message}")
        }
        return "127.0.0.1"
    }

    fun getLanAuthUrl(): String {
        return "http://${getLocalIpAddress()}:$actualPort/auth"
    }

    fun start(scope: CoroutineScope) {
        stop()
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = try {
                    ServerSocket(port)
                } catch (_: Exception) {
                    ServerSocket(0) // Fallback to random free port
                }
                actualPort = serverSocket?.localPort ?: port
                Log.i(TAG, "LAN Relay Auth Server running on ${getLanAuthUrl()}")

                while (isActive && serverSocket?.isClosed == false) {
                    val clientSocket = try {
                        serverSocket?.accept() ?: break
                    } catch (e: Exception) {
                        break
                    }
                    launch(Dispatchers.IO) {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "LAN Relay Server error: ${e.message}")
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val writer = PrintWriter(s.getOutputStream(), true)

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val path = parts[1]

                if (path.startsWith("/relay")) {
                    val query = path.substringAfter("?", "")
                    var capturedCode = ""
                    query.split("&").forEach { param ->
                        val kv = param.split("=")
                        if (kv.size == 2) {
                            val key = URLDecoder.decode(kv[0], "UTF-8")
                            val value = URLDecoder.decode(kv[1], "UTF-8")
                            if (key == "code") {
                                capturedCode = value
                            } else if (key == "url" && value.contains("code=")) {
                                capturedCode = value.substringAfter("code=").substringBefore("&")
                            }
                        }
                    }

                    if (capturedCode.isNotBlank()) {
                        onAuthCodeReceived(capturedCode)
                        sendSuccessHtml(writer)
                    } else {
                        sendErrorHtml(writer, "No se encontró ningún código de autorización.")
                    }
                } else {
                    sendPortalHtml(writer)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling client connection: ${e.message}")
        }
    }

    private fun sendPortalHtml(writer: PrintWriter) {
        val authLink = "https://login.live.com/oauth20_authorize.srf?client_id=${PrimaryAppIdAuthEngine.PRIMARY_APP_ID}&response_type=code&approval_prompt=auto&scope=${PrimaryAppIdAuthEngine.MBI_SSL_SCOPE}&redirect_uri=ms-xal-${PrimaryAppIdAuthEngine.PRIMARY_APP_ID}://auth"
        val html = """
            <!DOCTYPE html>
            <html lang="es">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>God Mode • Vinculación Xbox</title>
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #0c0e12; color: #fff; text-align: center; padding: 24px; margin: 0; }
                    .card { max-width: 440px; margin: 0 auto; background: #14181f; border-radius: 16px; padding: 24px; border: 1px solid #242c38; }
                    h2 { color: #107C10; margin-top: 0; }
                    p { color: #a0aec0; font-size: 14px; line-height: 1.5; }
                    .btn { display: block; width: 100%; box-sizing: border-box; background: #107C10; color: #fff; text-decoration: none; padding: 14px 20px; border-radius: 8px; font-weight: bold; margin: 16px 0; border: none; cursor: pointer; font-size: 15px; }
                    .btn:hover { background: #0b5e0b; }
                    input[type="text"] { width: 100%; box-sizing: border-box; background: #0c0e12; border: 1px solid #2d3748; padding: 12px; border-radius: 8px; color: #4ade80; margin: 8px 0; font-family: monospace; }
                    .step { background: #1c232d; padding: 12px; border-radius: 8px; text-align: left; margin: 12px 0; font-size: 13px; }
                </style>
            </head>
            <body>
                <div class="card">
                    <h2>God Mode • Relay LAN</h2>
                    <p>Inicia sesión en Xbox Live desde este dispositivo móvil para conectar tu consola o TV.</p>
                    
                    <div class="step">
                        <strong>Paso 1:</strong> Pulsa el botón abajo para abrir el inicio de sesión oficial de Microsoft en tu navegador.
                    </div>
                    
                    <a href="$authLink" target="_blank" class="btn">
                        Abrir Inicio de Sesión Microsoft
                    </a>
                    
                    <div class="step">
                        <strong>Paso 2:</strong> Al autorizar, si el navegador no puede abrir la app directamente o muestra una pantalla en blanco con <code>ms-xal-...://auth/?code=...</code>, copia esa dirección web y pégala aquí:
                    </div>
                    
                    <form action="/relay" method="GET">
                        <input type="text" name="url" placeholder="Pega aquí la URL de retorno o código" required />
                        <button type="submit" class="btn" style="background: #2563eb;">Transferir a la Consola</button>
                    </form>
                </div>
            </body>
            </html>
        """.trimIndent()

        writer.println("HTTP/1.1 200 OK")
        writer.println("Content-Type: text/html; charset=UTF-8")
        writer.println("Content-Length: ${html.toByteArray(Charsets.UTF_8).size}")
        writer.println("Connection: close")
        writer.println()
        writer.print(html)
        writer.flush()
    }

    private fun sendSuccessHtml(writer: PrintWriter) {
        val html = """
            <!DOCTYPE html>
            <html lang="es">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>¡Conexión Exitosa!</title>
                <style>
                    body { font-family: sans-serif; background: #0c0e12; color: #fff; text-align: center; padding: 48px 24px; }
                    .card { max-width: 400px; margin: 0 auto; background: #14181f; border-radius: 16px; padding: 32px; border: 1px solid #107C10; }
                    h2 { color: #4ade80; }
                </style>
            </head>
            <body>
                <div class="card">
                    <h2>✓ ¡Sesión Transferida!</h2>
                    <p>El código fue recibido correctamente por tu consola / Android TV.</p>
                    <p style="color: #94a3b8; font-size: 14px;">Ya puedes volver a la pantalla de God Mode.</p>
                </div>
            </body>
            </html>
        """.trimIndent()

        writer.println("HTTP/1.1 200 OK")
        writer.println("Content-Type: text/html; charset=UTF-8")
        writer.println("Content-Length: ${html.toByteArray(Charsets.UTF_8).size}")
        writer.println("Connection: close")
        writer.println()
        writer.print(html)
        writer.flush()
    }

    private fun sendErrorHtml(writer: PrintWriter, message: String) {
        val html = """
            <!DOCTYPE html>
            <html><body style="background:#0c0e12;color:#f87171;text-align:center;padding:48px;">
            <h3>Error al procesar el código</h3><p>$message</p>
            <a href="/auth" style="color:#60a5fa;">Intentar de nuevo</a></body></html>
        """.trimIndent()
        writer.println("HTTP/1.1 400 Bad Request")
        writer.println("Content-Type: text/html; charset=UTF-8")
        writer.println("Content-Length: ${html.toByteArray(Charsets.UTF_8).size}")
        writer.println("Connection: close")
        writer.println()
        writer.print(html)
        writer.flush()
    }
}
