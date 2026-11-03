# Plan de Implementación: Blindaje Crítico de los 6 Motores de Streaming, Cascada de Resiliencia Neuronal, Códecs de Última Generación y Calidad Extrema Forzada a 1440p / 1080p HQ (Estado 0)

Este documento unifica e integra la totalidad de los requisitos arquitectónicos y de ingeniería de bajo nivel para consolidar el pipeline de streaming nativo en C++20/Rust (`com.xcloud.godmode`), operando estrictamente en **Estado 0** (solo stream, sin interfaces gráficas, sin menús superpuestos, sin `WebView` y sin `FFmpeg`).

---

## 1. Ingeniería de Spoofing de Matriz de Xbox y Desbloqueo de Ancho de Banda (1440p / 35–50 Mbps)

Para que los servidores de Azure autoricen flujos de alta densidad superando las restricciones de clientes móviles o de suscripción estándar:

### 1.1 Inyección de Cabeceras de Dispositivo en Handshake HTTP (`/v5/sessions/cloud/play`)
- **Cabeceras de Dispositivo:**
  - `User-Agent`: Perfil de escritorio Windows x64.
  - `x-gssv-client`: `"XboxNative"`.
  - `x-ms-device-info`: Manifiesto emulando aplicación de escritorio/consola nativa:
    ```json
    {
      "appInfo": {
        "env": {
          "clientAppId": "XboxApp",
          "clientAppType": "native",
          "clientAppVersion": "2403.1001.4.0"
        }
      },
      "displayInfo": {
        "width": 2560,
        "height": 1440,
        "colorDepth": 24,
        "pixelDensity": 2.0
      }
    }
    ```
- **Payload de Sesión Forzado (`build_ultra_quality_session_payload` / `build_next_gen_session_payload`):**
  - Configuración con `osName: "windows"` o `"tizen"`, `sdkType: "native"`, `nanoVersion: "V3;RtspOverUdp"`.
  - Inyección explícita de `preferredVideoFormat`:
    ```json
    {
      "profile": "High",
      "maxWidth": 2560,
      "maxHeight": 1440,
      "maxframerate": 120
    }
    ```

### 1.2 Mensaje de Preferencia de Video (`VideoPreference`)
- Apenas se inicializa el canal de control WebSocket/SCTP, despacho de paquete JSON:
  ```json
  {
    "type": "VideoPreference",
    "width": 2560,
    "height": 1440,
    "fps": 60,
    "videoCodec": "H264",
    "colorSpaceFlags": 0
  }
  ```

### 1.3 Forzado de Bitrate Agresivo y Reescritura SDP (`apply_forced_high_bitrate_sdp_tweaks`)
- En `NativeZeroCopyPipeline::rewriteSdpForUltraQuality` e interceptores de descripción de sesión:
  - Inserción de límites de ancho de banda: `b=AS:35000` / `b=AS:45000` y `b=TIAS:44000000`.
  - Modificación de parámetros H.264 / HEVC en `fmtp`:
    - Sustitución de `max-rcv-bitrate=15000` por `max-rcv-bitrate=35000`.
    - Expansión de `packetization-mode=1` a `packetization-mode=1;max-br=35000`.
    - Regla de control de tasa: `a=fmtp:96 max-fr=120;max-fs=8704;profile-level-id=640032`.

---

## 2. Asignación Tecnológica y Mapeo de Códecs / IA por Motor (1 al 6)

Cada uno de los 6 motores nativos absorbe de forma polimórfica una tecnología específica de decodificación y de Inteligencia Artificial (Super-Resolución / Frame Generation):

### Motor 1: Rust Zero-Trust + Decodificador AV1/RAV1D Memory-Safe + AMD FSR 4 / AI Compute
- **Tecnología:** AV1 / AV2 (Libres de regalías) + AMD FSR 4 (Machine Learning Super Resolution).
- **Proceso:** `rav1d` parsea el flujo AV1 en Rust con seguridad estricta de memoria; entrega planos decodificados directamente a un Compute Shader de Vulkan con precisión `float16_t` para reescalado FSR 4 hacia la swapchain.
- **Alineación de Stride:** `copyPlanarYuvToVulkanImageWithStride` con `bufferRowLength = rustYStride` (1984/2048 bytes) para eliminar distorsión diagonal producida por padding SIMD NEON.

### Motor 2: Vulkan Video ASIC (`VK_KHR_video_decode_av1` / `hevc`) + Qualcomm GSR 2.0 / AFME 2.0
- **Tecnología:** AV1 / HEVC en silicio puro + Qualcomm Snapdragon Game Super Resolution 2.0 / Adreno Frame Motion Engine (AFME 2.0).
- **Proceso:** Decodificación directa en hardware mediante `vkCmdDecodeVideoKHR` sin intervención de CPU. Encadenamiento con núcleos tensoriales para generar fotogramas interpolados hasta 144 Hz.
- **Memoria de Sesión:** Asignación y vinculación obligatoria de memoria de sesión en VRAM (`vkGetVideoSessionMemoryRequirementsKHR` y `vkBindVideoSessionMemoryKHR`) antes de iniciar decodificación para evitar `SIGSEGV`.

### Motor 3: AMediaCodec AV1/HEVC + MediaTek Dimensity SR + APU Booster
- **Tecnología:** HEVC / AV1 (Android MediaCodec) + MediaTek Dimensity Super-Resolution y APU Booster.
- **Proceso:** Decodificación asíncrona de baja latencia con directivas exclusivas del silicio MediaTek (`vendor.mtk-ext-ai-sr.enable = 1`, `vendor.mtk-vdec-low-latency = 1`), permitiendo que el APU limpie macrobloques y ruido de compresión de forma asíncrona.

### Motor 4: HWC2 Directo (`ASurfaceTransaction`) + LCEVC (MPEG-5 Part 2) Layer Enhancement
- **Tecnología:** LCEVC (Capa Jerárquica de Mejora) + Hardware Composer 2 (`ASurfaceTransaction`).
- **Proceso:** Flujo base 1080p decodificado por hardware; la capa de realce LCEVC se parcha en el compositor físico de pantalla con costo de CPU casi nulo.
- **Blindaje EMFILE:** Extracción y cierre estricto de `releaseFenceFd` mediante `poll(&pfd, 1, 4)` y `close()` en el callback `onSurfaceTransactionComplete`, erradicando el crash a los 17 segundos.
- **Blindaje Buffer Starvation:** `AHardwareBuffer_acquire` + `AImage_delete` inmediato + `AHardwareBuffer_release` tras el escaneo de SurfaceFlinger.

### Motor 5: AMediaCodec + NPU NNAPI + Intel XeSS / DLSS 5.0 Generative AI Upscaling
- **Tecnología:** Redes Neuronales de Reconstrucción Profunda (DLSS / XeSS emulado en NNAPI) + `AHardwareBuffer` Zero-Copy.
- **Proceso:** Captura de `outputRgbaAhb` y derivación al grafo NNAPI compilado con `ANEURALNETWORKS_PREFER_SUSTAINED_SPEED`.
- **Modo Adaptativo Híbrido:** Base universal en `ANEURALNETWORKS_TENSOR_QUANT8_ASYMM` y escalamiento dinámico a `ANEURALNETWORKS_TENSOR_FLOAT16` en SoCs de gama alta (Snapdragon 8 Gen / Google Tensor).
- **Cierre del Grafo:** Invocación explícita de `ANeuralNetworksModel_finish` antes de compilar y presentación al plano `ASurfaceControl`.

### Motor 6: EGLImageKHR + OpenGL ES 3.2 OES + Pyrowave GPU Compute Shaders / Lossless Scaling (LSFG)
- **Tecnología:** Pyrowave (Compute Shaders en GPU) + Lossless Scaling Frame Generation (LSFG).
- **Proceso:** Mapeo de `AHardwareBuffer` a textura `samplerExternalOES` vía `EGLImageKHR`; ejecución de shader laplaciano con paso de texel adaptativo `uTexelStep` e interpolación de cuadros por cómputo GLES/Vulkan hasta 120 FPS.
- **Afinidad de Hilo EGL:** Desacople en hilo UI con `releaseContextFromUiThread` y vinculación en hilo de trabajo con `ensureBoundOnWorkerThread`, eliminando `EGL_BAD_ACCESS` y pantallas negras.

---

## 3. Cascada de Resiliencia Neuronal y Baja Latencia Absoluta

Controlador maestro `MasterSixEngineFailSafeController` implementando la conmutación en caliente en menos de 2 milisegundos ante fallos de hardware o incompatibilidad de drivers:
$$\text{Motor 3} \longrightarrow \text{Motor 4} \longrightarrow \text{Motor 5} \longrightarrow \text{Motor 6} \longrightarrow \text{Motor 1} \longrightarrow \text{Motor 2}$$

- Mantiene el mismo `ANativeWindow*` y reinyecta automáticamente los parámetros SPS/PPS almacenados en caché.
- Si un SoC de gama media no soporta 1440p nativo, auto-degradación transparente a 1080p HQ sin reiniciar la sesión.

---

## 4. Llaves de Latencia Ultra-Baja y Configuración Universal (`applyUltraQualityDecoderParameters`)

Función transversal aplicada a los decodificadores de hardware:
```cpp
void applyUltraQualityDecoderParameters(AMediaFormat* fmt, bool force1440p) {
    int targetW = force1440p ? 2560 : 1920;
    int targetH = force1440p ? 1440 : 1080;
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, targetW);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, targetH);
    AMediaFormat_setInt32(fmt, "low-latency", 1);
    AMediaFormat_setInt32(fmt, "max-bframes", 0);
    AMediaFormat_setInt32(fmt, "priority", 0);
    AMediaFormat_setInt32(fmt, "operating-rate", 120);
    AMediaFormat_setInt32(fmt, "vendor.qti-ext-dec-high-performance.enable", 1);
    AMediaFormat_setInt32(fmt, "vendor.mtk-ext-dec-high-quality.enable", 1);
    AMediaFormat_setInt32(fmt, "vendor.qti-ext-dec-max-bitrate.value", 50000000);
    AMediaFormat_setInt32(fmt, "vendor.mtk-vdec-max-bitrate", 50000000);
}
```

---

## 5. Blindaje de Red, Multiplexación y Protocolos de Sistema

1. **Demultiplexor RFC 7983 (`XcloudSinglePortDemuxer`):** Clasificación del socket UDP según el primer byte:
   - `0..3`: STUN
   - `20..63`: DTLS
   - `128..191` con PT 192..223: RTCP
   - `128..191` con PT 96..127: RTP
2. **Respondedor Automático STUN Consent (RFC 7675):** Respuesta inmediata (`0x0101`) a solicitudes `0x0001` con Magic Cookie `0x2112A442` en < 50 µs, impidiendo el corte de sesión a los 15 segundos.
3. **Salto de Cabeceras de Extensión RTP RFC 8285 (`0xBEDE` / `0x1000`):** `stripRtpHeadersAndExtensions` que salta CSRCs, extensiones de 1 y 2 bytes y descarta bytes de padding al final del datagrama para entregar NALs intactas.
4. **Handshake de Canales SCTP en Milisegundo Cero (`XcloudDataChannelBootstrapper`):** Despacho de `authorizationRequest`, `videoKeyframeRequested` y configuración de `systemUiIds` por los canales `control` y `message` para evitar el corte a los 5 segundos.
5. **Espera de Estado `/state`:** Verificación continua hasta obtener `Provisioned` o `ReadyToConnect` antes de activar el enlace de medios.
6. **Resolución Dinámica de Símbolos (`DynamicNdkSymbols`):** `dlsym` para APIs de `ASurfaceControl`, `ASurfaceTransaction`, `AMediaFormat_getRect` y NNAPI para compatibilidad total desde Android 8.0+.

---

## 6. HUD Hub de Ingeniería de Referencia (Telemetría Total)

Superposición en VRAM sin impacto en el rendimiento:
- **Vallas HWC2 y Descriptores:** Estado de `release_fence_fd`, latencia de poll y buffers retenidos.
- **Monitor STUN Consent / ICE:** Conexión UDP y contador de keep-alives recibidos y respondidos.
- **Telemetría de Red y Jitter Buffer:** RTT con filtro de Kalman, fluctuación y cola de paquetes en `BTreeMap`.
- **Identificación del Motor Activo:** Motor en ejecución (1 al 6), estado del reescalado AI (Vulkan / HWC / NPU / GLES), resolución detectada (1440p / 1080p) y FPS reales.

---

## 7. Reglas de Preservación ProGuard

```proguard
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.xcloud.godmode.streaming.** { *; }
-keep class com.xcloud.godmode.bridge.** { *; }
-keep class com.xcloud.godmode.models.** { *; }
```

---

## Verificación

1. **Compilación Limpia:** Ejecución de `compile_applet` en `arm64-v8a`, `armeabi-v7a` y `x86`.
2. **Pruebas Locales:** Ejecución de `:app:testDebugUnitTest`.
3. **Validación de Forzado a 1440p / 35-50 Mbps:** Comprobación del reordenamiento SDP, cabeceras `x-ms-device-info` y decodificación en los 6 motores.
