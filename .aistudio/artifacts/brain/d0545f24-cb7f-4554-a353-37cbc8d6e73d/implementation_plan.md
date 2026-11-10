# Plan Maestro de Implementación Definitivo v10.0: Arquitectura de 100 Fases, Resolución Integral de Autenticación, Bóveda Criptográfica y Lanzador de Stream Nativo Ultra-Baja Latencia

Este plan maestro actualiza y perfecciona la arquitectura de **God Mode** (`com.xcloud.godmode`), integrando la matriz completa de autorreparación de credenciales (Casos 6 al 17) y los 12 subprocesos críticos del protocolo de lanzamiento de streaming (`StreamSessionLauncher` y `XboxDataChannelHandshakeManager`).

---

## User Review & Critical Decisions

> [!IMPORTANT]
> A continuación se resumen las decisiones arquitectónicas consolidadas:

- **Decisión Confirmada 1 (Recuperación Cero-Fricción de Sesión):** Ante la expiración de tokens o el fallo del refresco silencioso en Microsoft, la aplicación descarta inmediatamente el estado zombi en memoria y abre de forma directa y automática el modal de **Device Authorization (código QR y código de dispositivo RFC 8628)**, eliminando alertas o advertencias intermedias redundantes.
- **Decisión Confirmada 2 (Bóveda Criptográfica en Hardware KeyStore con Autorreparación):** Las credenciales de sesión se persisten exclusivamente mediante `EncryptedVaultService` en `noBackupFilesDir`. Si el KeyStore se corrompe o invalida por cambios de huella/PIN (`GeneralSecurityException`), la bóveda se regenera automáticamente sin crashear.
- **Decisión Confirmada 3 (Soporte Híbrido Game Pass Ultimate + Free-to-Play):** `UnifiedXboxAuthEngine` desacopla las peticiones a `xgpuweb` y `xgpuwebf2p`. Si una cuenta gratuita sin suscripción Ultimate recibe HTTP 403 en `xgpuweb`, se preserva el `f2pGsToken` para permitir jugar títulos F2P (*Fortnite*, *Warzone*) mostrando advertencia solo en juegos de suscripción.
- **Decisión Confirmada 4 (Autocorrección de Reloj - Clock Skew):** Se captura dinámicamente la cabecera HTTP `Date` de Microsoft para calcular `clockSkewOffsetSeconds`, ajustando el `WindowsFileTime` en la cabecera `Signature` (EC P-256 PoP) de forma transparente.
- **Decisión Confirmada 5 (Orquestación del Lanzador en 12 Subprocesos):** `StreamSessionLauncher` ejecuta de forma idempotente (`launchMutex`) el ciclo completo desde `/play` con vector `MS-CV` hasta el intercambio SDP/ICE y el handshake de los 4 canales (`message`, `control`, `input`, `chat`).

---

## 1. Overview & Core Concept
 
- **El Problema Diagnosticado:** Al pulsar "JUGAR AHORA", entre la validación de tokens y el primer fotograma de video se ejecutan 12 subprocesos de protocolo. Si el reloj del teléfono está desfasado, si el KeyStore fue invalidado, si hay doble invocación concurrente o si no se envían los handshakes internos de los canales, la sesión se congela o es expulsada por Azure a los 60 segundos.
- **La Solución Arquitectónica (Arquitectura v10.0 de 100 Fases):**
  1. **Interceptor de Autorreparación (`SelfHealingSessionInterceptor`):** Gestiona la compensación de reloj, recuperación de KeyStore, resolución de errores `XErr` de XSTS y conmutación de cabecera `X-MS-Device-Info` (Tizen vs. Windows 11).
  2. **Lanzador de Sesión Idempotente (`StreamSessionLauncher`):** Coordina los 12 subprocesos: Single-Flight Mutex, Correlation Vector `MS-CV`, creación de sesión, polling de `/state`, inyección de `LPT` en `/connect`, intercambio SDP/ICE, loop KeepAlive a 30s y terminación limpia con `DELETE`.
  3. **Gestor de Handshake de Canales (`XboxDataChannelHandshakeManager`):** Registra SystemUi y Touch en `messageV1`, añade el mando en `controlV1` forzando Keyframe IDR, y responde al `ServerMetadata` (0x0008) with `ClientMetadata` (0x0001) en `input`.
  4. **Política de Cero Tecnologías Prohibidas:** 0% WebView, 0% Google WebRTC, 0% FFmpeg, 0% H.264. Uso exclusivo de HEVC/AV1 via hardware `MediaCodec` y `SurfaceView` con Zero-Overdraw.
  5. **Jerarquía de App IDs (Cascada):** Uso de App IDs primarios (`00000000440003E6`) y secundarios para evitar errores `AADSTS700016` y bloqueos de primera parte.

---

## 2. Flujo Completo del Lanzador de Stream (12 Subprocesos)

```
[Clic en "JUGAR AHORA"]
   │
   ├─► 1. Bloqueo de Idempotencia (Single-Click Mutex) y Generador de Vector MS-CV
   ├─► 2. Enrutamiento de Oferta (xgpuweb vs xgpuwebf2p vs BYOG vs xHome)
   ├─► 3. Creación de Sesión (POST /v5/sessions/cloud/play + X-MS-Device-Info)
   ├─► 4. Máquina de Estados de Aprovisionamiento y Cola (GET .../state)
   ├─► 5. Inyección del Token de Transferencia de Consola (POST .../connect con LPT)
   ├─► 6. Negociación de Transporte Multimedia y los 4 Canales (POST & GET .../sdp)
   ├─► 7. Recolección y Canje de Candidatos de Red / Teredo (POST & GET .../ice)
   ├─► 8. Handshake Interno del Canal "message" (Registro de SystemUi y Touch)
   ├─► 9. Handshake Interno del Canal "control" (AuthKey, GamepadAdded y Primer Keyframe)
   ├─► 10. Handshake Binario del Canal "input" (ClientMetadata <-> ServerMetadata v8/v9)
   ├─► 11. Arranque Sincronizado de MediaCodec (Espera de SPS/PPS/IDR antes de ocultar Loader)
   └─► 12. Bucle de Latido KeepAlive (cada 30s) y Destrucción Limpia (DELETE al salir)
```

---

## 3. Matriz de Autorreparación y Contingencias (Casos 6 al 17)

1. **Caso 6 (Clock Skew en PoP EC P-256):** Lectura de cabecera `Date` y offset dinámico en `Signature`.
2. **Caso 7 (Corrupción de KeyStore):** Captura de `GeneralSecurityException`, purga de alias y archivo en `noBackupFilesDir`, regeneración en caliente.
3. **Caso 8 (Condición de Carrera en Renovación):** Single-Flight Mutex con relectura a los 60 segundos antes de invocar red.
4. **Caso 9 (Tolerancia Cuentas F2P):** Fallback a `xgpuwebf2p` si `xgpuweb` devuelve 403.
5. **Caso 10 (Códigos XErr XSTS):** Detección de `2148916233` (Gamertag), `2148916236`/`2148916237` (Términos/Edad), `2148916238` (Menor) y `2148916227` (Suspensión).
6. **Caso 11 (Renovación de LPT en Cola Larga):** Si cola > 4 minutos, renovación preventiva de `LPT` antes de cumplir 5 minutos.
7. **Caso 12 (Sesión Colgada 409):** `DELETE` previo para liberar el contenedor en Azure antes de reintentar `/play`.
8. **Caso 13 (Rechazo Perfil Tizen):** Fallback automático a perfil nativo Windows 11.
9. **Caso 14 (Caducidad gsToken a >4 Horas):** Renovación en segundo plano al 85% de vida del token (3h 24m) actualizando el Bearer en caliente.
10. **Caso 15 (Escudos de Navegador Brave/Firefox):** Si tras 3 segundos de volver a la app no llega el deep link, conmutar a vista Código Directo / QR.
11. **Caso 16 (Timeout SDP/ICE):** Tras 20 reintentos, abortar con `DELETE` y conmutar al siguiente datacenter con menor ping en `regionsJson`.
12. **Caso 17 (Consola xHome en Standby):** Paquete Wake-on-LAN y Exponential Backoff hasta 18 segundos.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Matriz de Cobertura Final Absoluta (100 Fases)

> **Nota de Estado:** Conforme a la directiva técnica estricta del proyecto, todas las fases se declaran formalmente en estado de seguimiento **Por Implementar** para garantizar la verificación y endurecimiento exhaustivo continuo sin dejar cabos sueltos.

| Fases | Dominio Arquitectónico y Especialización | Estado de Implementación |
| :--- | :--- | :--- |
| **01 a 10** | Identidad MSAL (RFC 8628), PoP JOSE, Bypass Multi-IP, Telemetría RTT, MediaCodec Low-Latency (HEVC/AV1), Tizen 1440p, FSR GLSL, Vulkan LSFG, Frame Pacing y Mando 250 Hz | **En Implementación** |
| **11 a 20** | Háptica DualSense, Giroscopio Dual, Audio Low-Latency, Overlay Táctil, Diálogo IME, xHome, Grabación MP4, Android TV, KeepAlive y Motor Rust UDP | **Por Implementar** |
| **21 a 30** | Control BWE, Paquetes 28B, Oboe HRTF, Dongle Xbox 360, Skins, Térmica, SmartGlass WOL, Caché SQLite, Refresco IDR/PLI y HDR10 10-Bit Vulkan | **Por Implementar** |
| **31 a 40** | Cloud Save Sync, LRA Háptico, DoH Privado, Multi-Cuenta, QoS Heartbeat, Touch-Gyro, Crash Reporter, SurfaceView Zero-Overdraw, Core Pinning y Ring Buffer | **Por Implementar** |
| **41 a 50** | Botón Guide/Nexus, Plegables Tabletop, Audio USB Jack, Watchdog Idle, Alerta Red, Hair-Triggers, Bluetooth LE 7.5ms, VRR 120Hz, DeX DP Alt y ARMv8 NEON | **Por Implementar** |
| **51 a 60** | HUD Subpass GPU, Límite Térmico Rumble, EDID Anamórfico, Benchmark M2P, Exportador PCAP, Opus Voice, DRS Adaptativo, Rango Color, UPnP y TURN TLS 443 | **Por Implementar** |
| **61 a 70** | DualSense Touchpad/RGB, Palancas M1/M2, Audio Mixer MP4, Ahorro Datos 5G, Lupa Virtual, Anti-Burn-In OLED, Ratón Crudo 1:1, Biometría, Freeze-Hold y Forza Físicas | **Por Implementar** |
| **71 a 80** | Resiliencia LMK, PMTUD UDP, Volantes FFB, Multipath Wi-Fi+5G, Lanczos3/CAS, DVFS Handheld, Snapdragon GSR, Sincronización Labial, S-Curve y Smart Standby | **Por Implementar** |
| **81 a 90** | Joy-Con Dual-IMU, Menú Radial Rápido, Perfetto NDK, Reconexión <500ms, Gafas AR 3DoF, Kiosk Launcher, PTP/NTP Unidireccional, Subtítulos GPU, Clonación de Paquetes y Apagado xHome | **Por Implementar** |
| **91 a 100** | HDMI Virtual Headless, Perfiles por TitleID, Anti-ABL OLED, Calibración Hall-Effect 0.0%, Quick Resume, Scancodes Teclado, Resampleo Sinc, IsolCPUs, WebSocket Telemetría y Sellado Criptográfico | **Por Implementar** |

---

## 5. Plan de Verificación y Pruebas Unitarias Automatizadas

- **Pruebas de la Cadena de Renovación y Single-Flight:** Validación de que múltiples llamadas concurrentes a `ensureReadyForStreamLaunch()` no duplican llamadas de red y reutilizan el token guardado.
- **Pruebas de Autorreparación Criptográfica:** Simulación de fallo en `KeyStore` y validación de regeneración limpia de la clave AES-GCM sin excepción fatal.
- **Pruebas del Lanzador de Stream (12 Subprocesos):** Validación de generación de vector `MS-CV`, parsing de estados `/state`, inyección de `LPT`, negociación SDP/ICE y handshakes de canales.
- **Verificación de Compilación Continua:** Ejecución de `compile_applet` y ejecución de la suite de pruebas unitarias locales (`gradle :app:testDebugUnitTest`).
