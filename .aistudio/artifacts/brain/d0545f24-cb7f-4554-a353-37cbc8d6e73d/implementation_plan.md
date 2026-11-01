# Plan de Implementación Técnica Consolidado: Arquitectura de 5 Capas y Blindaje Extremo del Stream Pipeline ("God Mode")

> [!IMPORTANT]
> **Directiva de Integridad y Aislamiento Estricto:** La ejecución de este plan se concentra **exclusivamente en el pipeline de streaming** (decodificación zero-copy, audio Oboe, periféricos a 250 Hz, control de congestión, QoS de red, sincronización A/V y sanitización volátil). Todos los componentes de las Capas 1, 4 y 5 preexistentes (Lanzador Cinema Hybrid, Modo Mica, insignias oficiales ESRB/PEGI, autenticación de 7 vías y catálogo dinámico) se mantienen 100% inalterados e inmutables.

---

## 1. Alineación Arquitectónica Canónica por Capas

El ecosistema nativo se organiza en una matriz de 5 capas funcionales estrictamente desacopladas, complementadas por el submódulo aislado de inteligencia artificial:

| Capa Arquitectónica | Tecnologías Clave | Estado y Alcance en esta Iteración |
| :--- | :--- | :--- |
| **Capa 1: Presentación e Interfaz** | Jetpack Compose, Material Design 3, Coil Compose | **Inmutable / Preservada:** Lanzador Cinema Hybrid (120 Hz), píldora flotante translúcida Modo Mica (`#090D13`), insignias oficiales ESRB/PEGI y controles táctiles. |
| **Capa 2: Motor Multimedia NDK & Zero-Copy** | C++20, Android NDK, AMediaCodec, ANativeWindow, EGL, OpenGL ES 3.2 | **Foco Principal (Stream):** Decodificación hardware directa sin copias en RAM, render pass separado para telemetría, soporte multi-códec (AV1, HEVC, VVC, LCEVC, SVC) y búfer SPSC a 250 Hz. |
| **Capa 3: Audio de Baja Latencia & Háptica** | Oboe C++, AAudio, OpenSL ES, Android Vibrator | **Foco Principal (Stream):** Canal exclusivo `SCHED_FIFO`, ráfagas adaptativas, filtro Biquad a 120 Hz para haptics, Opus PLC y compensación dinámica de PTS para Bluetooth ($\pm 5\text{ ms}$). |
| **Capa 4: Red, Conectividad & Protocolos** | OkHttp, DoH 1.1.1.1, WebRTC, JoseCripto, DiffServ QoS | **Foco Principal (Stream):** Priorización DSCP (EF 46 / AF41 34), Wi-Fi WMM Low-Latency, mitigación de Buffer Bloat ($\frac{dRTT}{dt}$), Watchdog ICE/DTLS (1200 ms), FlexFEC-03 y bypass anti-relay. *(Autenticación 7-vías permanece inalterada).* |
| **Capa 5: Persistencia, Estado & Sanitización** | Room Database, DataStore, Android Keystore, C++ Memory Vault | **Foco Principal (Stream):** Secure RAM Vault cifrado en C++ (AES-GCM), sanitización Zero-Trace post-sesión (`memset_s`) y purga de directorios caché en frío. *(Base de datos Room y catálogo dinámico permanecen inalterados).* |
| **Submódulo Especializado: IA Operativa** | Firebase AI Logic / Gemini API (`com.xcloud.godmode.ai`) | **Aislado:** Asistencia y optimización gráfica desacoplada de la ruta crítica de streaming. |

---

## 2. Diagrama del Sistema: Flujo Multimedia y Blindaje Extremo (Extensiones 1 a 10)

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                   PIPELINE DE STREAMING HOLÍSTICO NATIVO "GOD MODE"                              │
└──────────────────────────────────────────────┬───────────────────────────────────────────────────┘
                                               │
     ┌───────────────────┬─────────────────────┼─────────────────────┬───────────────────┐
     ▼                   ▼                     ▼                     ▼                   ▼
┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
│ ENRUTAMIENTO,   │ │ DECODIFICACIÓN  │ │ CONTROLADORES,  │ │ AUDIO OBOE,     │ │ SEGURIDAD,      │
│ QoS & WATCHDOG  │ │ ZERO-COPY & GPU │ │ TOUCH & SPSC    │ │ SYNC & HAPTICS  │ │ AUDIT & GHOST   │
│ (Ext. 1, 2, 3,5)│ │ (Ext. 6)        │ │ (Ext. 8)        │ │ (Ext. 4)        │ │ (Ext. 7, 9, 10) │
├─────────────────┤ ├─────────────────┤ ├─────────────────┤ ├─────────────────┤ ├─────────────────┤
│• DiffServ QoS   │ │• AMediaCodec    │ │• Ring Buffer    │ │• Oboe LowLatency│ │• Secure RAM     │
│  EF 46 / AF41 34│ │  Zero-Copy HW   │ │  SPSC C++ 250Hz │ │  Adaptive Bursts│ │  Vault (C++ AES)│
│• Wi-Fi WMM Lock │ │• EGL Fence Sync │ │• Kalman Filter  │ │• A/V Sync (PTS  │ │• Zero-Trace     │
│• Buffer Bloat   │ │• Dynamic NN     │ │  Mouse Aiming   │ │  Compensación)  │ │  Shutdown Memset│
│  dRTT/dt Mon.   │ │  Offload (>42°C)│ │• Touch De-jitter│ │• Opus PLC Audio │ │• Purga Caché    │
│• ICE Watchdog   │ │• AChoreographer │ │  Zonas Muertas  │ │• 120Hz Biquad   │ │  en Frío        │
│  SCTP (1200ms)  │ │  120Hz VSync    │ │  Elípticas      │ │  Audio Haptics  │ │• Ghost HUD Core │
│• FlexFEC / NACK │ │• eglCleanBinding│ │• EMA Smoothing  │ │• Bluetooth Offs │ │  EGL Overlay    │
└─────────────────┘ └─────────────────┘ └─────────────────┘ └─────────────────┘ └─────────────────┘
```

---

## 3. Plan Detallado de Implementación por Fases de Stream

### Fase 1: Señalización SDP, Priorización QoS DiffServ, Wi-Fi WMM y Watchdog ICE (Ext. 1, 2, 3 y 5)
- **Componentes:** `app/src/main/cpp/stream/NativeStreamPipeline.cpp`, `app/src/main/java/com/xcloud/godmode/streaming/WebRTCStreamManager.kt` y `app/src/main/java/com/xcloud/godmode/streaming/AdaptiveBitrateController.kt`
- **Tareas:**
  1. **Marcado DiffServ QoS (DSCP / ToS):** Configurar en los sockets de transporte UDP de WebRTC: `DSCP 46 (EF)` para paquetes de entrada SCTP y voz; `DSCP 34 (AF41)` para video interactivo; y `DSCP 0 (BE)` para señalización auxiliar.
  2. **Bloqueo Wi-Fi WMM de Ultra-Baja Latencia:** Invocar `WifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, ...)` al iniciar el stream para abatir la contención local en el router a <2 ms.
  3. **Mitigación de Buffer Bloat:** Analizar la derivada temporal del RTT ($\frac{dRTT}{dt}$) junto al Packet Burst Tracing en reportes RTCP; ante aumentos silenciosos de cola, reducir preventivamente la tasa de bits 50 ms antes de la saturación.
  4. **Watchdog de Enlace ICE/DTLS:** Inyectar heartbeats en DataChannel cada 500 ms. Si se detectan más de 1200 ms sin confirmación de tráfico, ejecutar automáticamente `peerConnection.restartIce()` en segundo plano sin destruir sockets ni sesión RPS en <1.2 s.
  5. **Reescritor SDP Multi-Códec & FlexFEC-03:** Mutación estricta de SDP para priorizar VVC (RFC 9328), AV1, HEVC Main 10 HDR, LCEVC y H.264 SVC con inyección de `flexfec-03/90000` y retroalimentación reactiva (`nack`, `nack pli`, `transport-cc`, `goog-remb`). Descarte activo de candidatos TURN (`typ relay`) y mDNS (`.local`).

### Fase 2: Pipeline Híbrido Zero-Copy, Fences EGL y Descarga Neuronal Térmica (Ext. 6)
- **Componentes:** `app/src/main/cpp/NativeStreamBridge.cpp`, `app/src/main/cpp/stream/NativeStreamPipeline.cpp` y `app/src/main/java/com/xcloud/godmode/ui/screens/StreamScreen.kt`
- **Tareas:**
  1. **Decodificación Hardware Zero-Copy:** Vinculación directa de `AMediaCodec` asíncrono con `ANativeWindow` en modo VSync-Off / Latencia cero.
  2. **Aislamiento de Ciclo de Vida EGL:** Invocación de `cleanupEglSurfaceBinding()` (`eglMakeCurrent(EGL_NO_SURFACE)`) al detener o pausar el stream, evitando el error `ANativeWindow already connected to another API`.
  3. **Dynamic NN Scaler Offload (>42°C):** Monitoreo térmico mediante JNI; ante temperaturas elevadas del silicio, conmutar kernels de FSR a perfil "Lite" y pausar temporalmente LSFG para sostener 60 FPS sin caídas por *thermal throttling*.

### Fase 3: Buffer SPSC a 250 Hz, Filtro Kalman y Touch De-jitter Elíptico (Ext. 8)
- **Componentes:** `app/src/main/cpp/stream/NativeStreamPipeline.h`, `app/src/main/java/com/xcloud/godmode/streaming/NativeInputBridge.kt` y `app/src/main/java/com/xcloud/godmode/ui/components/GodModeTouchControlsOverlay.kt`
- **Tareas:**
  1. **Cola Circular SPSC Lock-Free C++20:** Estructura circular con alineación a 64 bytes (`alignas(64)`) y operaciones atómicas `acquire`/`release` para sondeo a 4 ms (250 Hz) sin contención de mutex.
  2. **Filtro de Kalman para MKB:** Suavizado predictivo en `smoothMouseDelta` que elimina el micro-jitter del ratón sin introducir latencia de apuntado.
  3. **Touch De-jitter Avanzado:** Filtro geométrico con zonas muertas concéntricas y elípticas adaptables por perfil, complementado por suavizado de trayectoria exponencial (EMA) para los joysticks virtuales.

### Fase 4: Audio Oboe, Compensación A/V Bluetooth y Háptica Biquad (Ext. 4)
- **Componentes:** `app/src/main/java/com/xcloud/godmode/audio/OboeAudioBridge.kt`, `app/src/main/cpp/godmode_ndk_engine.cpp` y `app/src/main/java/com/xcloud/godmode/haptics/UnifiedHapticTelemetricEngine.kt`
- **Tareas:**
  1. **Ráfagas Adaptativas Oboe:** Ajuste dinámico de buffers (64–256 muestras) en modo `PerformanceMode::LowLatency` con prioridad del kernel Linux `SCHED_FIFO`.
  2. **A/V Sync Compensation Engine:** Medición del retardo en dispositivos Bluetooth (`PROPERTY_OUTPUT_FRAMES_PER_BUFFER`) y desfase temporal de PTS en video, manteniendo la sincronía audio/video en $\pm 5\text{ ms}$.
  3. **Transducción Háptica Biquad (120 Hz) & PLC:** Filtro pasobajo para convertir frecuencias graves en vibración háptica, junto a Packet Loss Concealment (PLC) para evitar artefactos acústicos.

### Fase 5: Ghost HUD de Telemetría, Bóveda Segura y Sanitización Zero-Trace (Ext. 7, 9 y 10)
- **Componentes:** `app/src/main/java/com/xcloud/godmode/ui/components/GodModeTelemetryOverlay.kt`, `app/src/main/cpp/SecureRamVault.cpp`, `app/src/main/java/com/xcloud/godmode/streaming/WebRTCStreamManager.kt` y `app/src/main/cpp/stream/NativeStreamPipeline.cpp`
- **Tareas:**
  1. **Ghost HUD en Capa Gráfica Independiente:** Renderizado de métricas de diagnóstico (RTT, jitter, FPS, pérdida de paquetes, bitrate real y resolución) sobre superficie gráfica desacoplada (0.0% impacto en Compose).
  2. **Secure RAM Vault en C++:** Cifrado simétrico AES-GCM en memoria nativa para credenciales y tokens volátiles XSTS/OAuth con claves de Android Keystore.
  3. **Zero-Trace Shutdown:** Sobrescritura de memoria con ceros (`memset_s`) en buffers NDK (ANativeWindow, texturas EGL, SPSC Ring Buffers) al finalizar o interrumpir la transmisión.
  4. **Purga de Caché en Frío:** Limpieza síncrona de particiones temporales de decodificación en `context.cacheDir` y `context.externalCacheDir`, imposibilitando la recuperación forense de datos o fotogramas en dispositivos compartidos.

### Fase 6: Validación de Integridad, Compilación y Pruebas Unitarias
- **Tareas:**
  1. Verificación completa de compilación (`compile_applet`).
  2. Pruebas unitarias de regresión (`gradle :app:testDebugUnitTest`).
  3. Auditoría estricta de nombres y políticas: espacio de nombres canónico `com.xcloud.godmode.*` y erradicación total de marcas o repositorios de terceros.

---

## 4. Garantía de Inmutabilidad y No Regresión
- No se modificará ninguna interfaz de usuario, pantalla, tema, lanzador Cinema Hybrid ni navegación existente.
- Toda modificación estará estrictamente contenida en los módulos de streaming (`.streaming`), audio/háptica (`.audio`, `.haptics`), seguridad volátil y los fuentes nativos C++20 en `app/src/main/cpp/`.
