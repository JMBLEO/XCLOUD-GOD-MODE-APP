# God Mode v3.0: Arquitectura Nativa de Ultra-Baja Latencia (C++20 / Rust NDK, Zero-Copy Pipeline & Cinema Hybrid Launcher)

Motor de cloud gaming y consola remota de alto rendimiento que desacopla la ruta crítica de streaming de la máquina virtual Android mediante un pipeline multimedia zero-copy en C++20 y Rust, eliminando pausas de recolección de basura (GC), unificando el renderizado de interfaz y video en una superficie acelerada por hardware con Render Passes independientes para video y telemetría HUD, integrando el lanzador cinemático *Cinema Hybrid* a 120Hz, soporte nativo Picture-in-Picture (PiP) y control táctil directo por intercepción de eventos de hardware.

## Decisiones Críticas y Revisión del Usuario

> [!IMPORTANT]
> A continuación se consolidan las especificaciones arquitectónicas aprobadas y las extensiones estructurales incorporadas en esta revisión:

- **Lanzador Cinemático *Cinema Hybrid* a 120Hz**: Interfaz de consola con barra lateral retráctil (`SideNavigationRail`), carrusel espacial con navegación fluida por D-Pad (`Modifier.focusable()` con `interactionSource`), borde de enfoque dorado (`#FFD700`) con escalado a 1.06x sin recomposiciones costosas, fondo cinemático reactivo con degradado horizontal oscuro al arte del juego enfocado, barra de atajos dinámicos de mando en la base (A Detalles, ZR Jugar, X Favorito, Y Buscar) y modales de acción rápida contextuales.
- **Capa NDK Zero-Copy y HUD Desacoplado**: Renderizado del stream mediante `AndroidView` vinculado a un `SurfaceView` que entrega su `ANativeWindow` directamente a `AMediaCodec` asíncrono en C++20. La telemetría HUD (resolución, FPS, bitrate, RTT y latencia local de decodificación) se procesa mediante una pasada EGL/OpenGL ES independiente sobre la misma superficie, impidiendo que los filtros de escalado del video (FSR / CAS) distorsionen el texto de telemetría.
- **Picture-in-Picture (PiP 16:9) y Menú Guía ("Go Back To")**: Integración nativa de `onUserLeaveHint` con `PictureInPictureParams` en relación 16:9 para mantener la sesión de juego activa al navegar por el sistema operativo, y panel lateral translúcido *Glassmorphism* in-stream para gestionar amigos, logros TrueAchievements y ajustes en vivo (V-Sync, háptica, giroscopio) sin destruir el socket WebRTC.
- **Controles Táctiles de Latencia Cero (Raw Touch Bypass)**: Evasión de la jerarquía de eventos de Android (`ViewRootImpl`) sobrescribiendo `dispatchTouchEvent` a nivel del contenedor raíz para inyectar coordenadas crudas (X, Y, presión) directamente a `NativeInputBridge` en C++/Rust, empaquetando y transmitiendo el estado al DataChannel en menos de 2ms con corrección radial de zonas muertas.
- **Red y Control de Congestión Extremo**: BBRv2 en Rust, reordenamiento RTP nativo con gracia NACK dinámica, inyección SDP desbloqueada (`b=AS:50000` / `b=TIAS:50000000`), QoS DSCP `0x2E`, FEC adaptativo y supervivencia GSSV multi-offering (`xgpuweb`, `xgpucatalog`, `xgpuwebf2p`, `xhome`).
- **Aislamiento de Kernel y Hardware**: CPU Core Pinning a núcleos de rendimiento (P-Cores), audio directo AAudio/Oboe exclusivo en `SCHED_FIFO`, espacialización binaural HRTF 5.1, colas SPSC mutex-free y watchdog anti-ANR de reinicio en <16ms.

---

## 1. Visión General y Concepto Central

### Qué Hace la Aplicación
God Mode v3.0 es una plataforma cliente nativa e independiente para Xbox Cloud Gaming y Xbox Remote Play optimizada para smartphones, tablets, consolas portátiles y Android TV. Integra un lanzador cinemático de consola a 120 FPS con navegación espacial por D-Pad, decodificación por hardware zero-copy (AV1, HEVC Main 10, VVC, LCEVC) directamente sobre `ANativeWindow`, telemetría HUD con Render Pass independiente, modo Picture-in-Picture sin desconexión, y controles físicos y táctiles de respuesta instantánea.

### Audiencia Objetivo
Jugadores que buscan una experiencia de consola de salón y portátil de nivel competitivo, exigiendo la mínima latencia de fotograma posible, negros puros en pantallas OLED, navegación táctil o con mando sin demoras, y continuidad ininterrumpida de sus sesiones de juego.

### Propuesta de Valor Clave
- **Determinismo Libre de GC**: Ruta crítica de red, video, audio y entrada ejecutada en C++20 y Rust.
- **Fidelidad Visual y Rendimiento**: Presentación inmediata VSync-Off (`eglSwapInterval(0)`), shaders CAS y soporte HDR10+ / Dolby Vision.
- **Ergonomía de Consola**: Interfaz 10-foot UI optimizada para control total sin tocar la pantalla, con modales de acción rápida y fondos reactivos inmersivos.

---

## 2. Experiencia de Usuario y Diseño Visual

### 2.1. El Lanzador Cinemático *Cinema Hybrid* (Home / Hub)

- **Fondo Cinemático Reactivo**: Al navegar y cambiar el foco entre títulos en el carrusel (ej. *Forza Horizon 5*, *Starfield*), el fondo completo de la aplicación transiciona suavemente hacia el Key Art del juego enfocado, fusionado con un degradado horizontal oscuro (`#070A0E` a transparente) para asegurar una legibilidad impecable de los textos y tarjetas.
- **Navegación Espacial y Filtrado**:
  - **Barra Lateral Colapsable (`SideNavigationRail`)**: Iconos limpios para "Inicio (Cloud Gaming)", "Mis Consolas (xHome)" y "Configuración", expandiéndose elegantemente al recibir el foco.
  - **Píldoras de Filtrado Rápido**: Selectores horizontales inmediatamente debajo del buscador ("Todos", "Recientes", "Optimizados Series X|S", "Multijugador", "Juegos Propios / BYOG").
  - **Filas de Contenido**: "Continuar Partida / Jump back in" para reanudación instantánea y "Cloud Gaming" para el catálogo general.
- **Tarjetas de Juego con Enfoque a 120Hz**:
  - Efecto de foco animado a 1.06x (`animateFloatAsState`) al recibir selección por D-Pad.
  - **Borde de Enfoque Dorado (`#FFD700`)**: Contorno dorado de 2dp con esquinas redondeadas (`RoundedCornerShape(12.dp)`), eliminando recomposiciones pesadas mediante `Modifier.focusable(interactionSource)`.
- **Barra de Atajos de Mando Inferior**: Barra persistente en la parte inferior con prompts dinámicos adaptados al mando conectado (`(A) Detalles`, `(ZR) Jugar`, `(X) Favorito`, `(Y) Buscar`).
- **Modales de Acción Rápida**: Al pulsar 'A' en un título, se despliega un diálogo flotante centrado con desenfoque de fondo ("Jugar ahora", "Añadir a favoritos", "Ver detalles", "Cancelar"), permitiendo iniciar la partida en un toque.

### 2.2. Reproductor Nativo Zero-Copy y Menú Guía In-Stream

- **Superficie de Video Directa (`NativeStreamView`)**: Contenedor `SurfaceView` cuya superficie nativa es entregada a C++20, permitiendo que `AMediaCodec` escriba los fotogramas decodificados directamente en el hardware de visualización.
- **HUD de Telemetría Flotante**: Barra superior derecha renderizada en una pasada EGL/OpenGL ES independiente sobre la superficie, mostrando en microsegundos:
  ```text
  1440p · 60 fps · 45.2 Mbps · rtt 18 ms · local 4 ms
  ```
- **Menú Guía In-Game ("Go Back To")**: Panel lateral translúcido con efecto *Glassmorphism* invocado mediante el botón Nexus / Home del mando o gesto lateral, que suspende el envío de inputs pero mantiene el socket WebRTC activo en segundo plano, con pestañas:
  - **Juego**: Reanudar o salir de la sesión activa.
  - **Amigos y Logros**: Lista de amigos en línea y progreso TrueAchievements.
  - **Configuración en Vivo**: Toggles para V-Sync (activar/desactivar), selector de Vibración (Háptica 4 canales), Motion Aiming (Giroscopio) y Controles Táctiles (On/Off).

### 2.3. Controles Táctiles de Latencia Cero

- **Layout Ergonómico**: Joysticks virtuales translúcidos y botones ABXY en la zona inferior derecha; gatillos superiores (LT, LB, RT, RB) en disposición de fácil alcance índice/pulgar; botones L3/R3 adyacentes a los analógicos.
- **Captura Cruda en `dispatchTouchEvent`**: Intercepción directa que elude el pipeline de eventos de Android, enviando punteros multitáctiles (ID, X, Y, acción) a `NativeInputBridge` para su conversión analógica y despacho WebRTC en menos de 2ms.

---

## 3. Decisiones Arquitectónicas y Compromisos Técnicos

### Decisión 1: Pipeline Zero-Copy y Render Passes Separados (Video vs HUD)
- **Enfoque**: `AMediaCodec` asíncrono escribe directamente a `ANativeWindow`. La telemetría HUD y la interfaz superpuesta se dibujan mediante shaders en una pasada separada con EGL / OpenGL ES.
- **Razón**: Los algoritmos de escalado espacial y nitidez de video (CAS / FSR) provocan artefactos y borrosidad en textos y tipografías pequeñas. Separar los pases garantiza video mejorado y texto de telemetría a resolución nativa cristalina.

### Decisión 2: Multitarea Picture-in-Picture (PiP 16:9) Nativo
- **Enfoque**: Manejo de `onUserLeaveHint` en `MainActivity` para configurar `PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()` y pasar a modo PiP.
- **Razón**: Permite al usuario consultar mensajes en el teléfono o navegar al Home sin que el sistema operativo congele o destruya la sesión WebRTC, evitando reingresar a colas de espera en los servidores.

### Decisión 3: Intercepción Cruda de Toques Táctiles
- **Enfoque**: Sobrescritura de `dispatchTouchEvent` en el contenedor principal con envío directo por JNI a la lógica de entrada nativa en C++/Rust.
- **Razón**: El enrutamiento convencional a través del árbol de vistas de Android (`ViewRootImpl`) agrega entre 15ms y 25ms de latencia de entrada. El puente directo garantiza paridad de respuesta con un mando físico (inferior a 4ms).

### Decisión 4: Gestión de Estado Desacoplada (`StreamingSettings` & `AudioSettings`)
- **Enfoque**: Estados inmutables expuestos vía StateFlow que sincronizan proactivamente la resolución (1440p QHD / 1080p), bitrate (hasta 50+ Mbps), códec preferido (HEVC/AV1/VVC) y ganancia de audio con el orquestador nativo en Rust.

---

## 4. Arquitectura del Sistema y Flujo de Componentes

```
┌────────────────────────────────────────────────────────────────────────┐
│               GOD MODE CINEMA HYBRID CLIENT (v3.0)                     │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
       ┌───────────────────────────┴───────────────────────────┐
       ▼                                                       ▼
 ┌───────────────────────────────────────┐   ┌───────────────────────────────────────┐
 │        CAPA VISUAL Y LAUNCHER         │   │          MOTOR NATIVO NDK             │
 │         (Jetpack Compose UI)          │   │           (C++20 & Rust)              │
 ├───────────────────────────────────────┤   ├───────────────────────────────────────┤
 │ • CinemaHybridLauncher (120Hz D-Pad)  │   │ • AMediaCodec Asíncrono Zero-Copy     │
 │ • Fondo Dinámico con Key Art Reactivo │   │ • Frame Pacing VSync-Off Inmediato    │
 │ • GameCardFocusable (Borde Dorado)    │   │ • Shader Render Pass HUD Separado     │
 │ • SideNavigationRail y Modales        │◄─►│ • Reordenamiento RTP & BBRv2 Congest. │
 │ • Menú Guía Translúcido In-Stream     │   │ • AAudio / Oboe Direct (SCHED_FIFO)   │
 │ • Picture-in-Picture (PiP 16:9)       │   │ • Sondeo de Mandos 250Hz & Curvas L2  │
 │ • Zero-Latency Raw Touch Dispatch     │   │ • CPU Core Pinning a P-Cores          │
 └───────────────────────────────────────┘   └───────────────────────────────────────┘
                                   │
                                   ▼
 ┌───────────────────────────────────────────────────────────────────┐
 │               CASCADA DE AUTENTICACIÓN MULTI-OFFERING             │
 ├───────────────────────────────────────────────────────────────────┤
 │ • OAuth 2.0 Device Code con Generación QR en Pantalla             │
 │ • Intercambio XBL RPS & Tokens XSTS Duales                        │
 │ • Smart Fallback Concurrente: xgpuweb ➔ byod ➔ f2p ➔ xhome        │
 │ • Fast Session Resume (<3s) & TokenVault Cifrado AES-256-GCM      │
 └───────────────────────────────────────────────────────────────────┘
```

### Modelos de Estado y Componentes Clave

1. **`models/`**:
   - `StreamingSettings`: Resolución (`1440p (QHD)`, `1080p HQ`), bitrate (`45 Mbps`), códec preferido (`HEVC`, `AV1`, `VVC`).
   - `AudioSettings`: Formato (`Opus estéreo`, `Binaural 5.1`), nivel de ganancia acústica.
2. **`ui/screens/` & `ui/components/`**:
   - `HomeScreen.kt`: Integración del lanzador cinemático `CinemaHybridLauncher` con barra lateral y carrusel espacial.
   - `AdaptiveHeroCarousel.kt` & `GameCardFocusable`: Tarjetas con borde dorado (`#FFD700`), escalado 1.06x y captación de foco D-Pad a 120Hz.
   - `StreamScreen.kt` & `ZeroCopyStreamView`: Contenedor del stream sobre `SurfaceView` enlazado al NDK.
   - `InStreamGameBar.kt`: Menú guía lateral translúcido con pestañas y controles en vivo.
   - `LatencyHUD.kt`: HUD de telemetría flotante con micro-métricas en tiempo real.
3. **`streaming/` & `bridge/`**:
   - `GodModeAndroidBridge.kt`: Conexión JNI para `bindNativeSurface`, `unbindNativeSurface`, inyección de toques táctiles crudos y configuración en caliente de resolución.
   - `NativeInputBridge.kt`: Despacho de paquetes a 250Hz con corrección radial y supresión de reposo.
4. **`network/`**:
   - `GodModeAuthOrchestrator.kt`: Cascada de autenticación multi-offering sin bloqueos.
   - `WakeOnLanManager.kt`: Descubrimiento local SSDP y paquetes mágicos para streaming xHome.
