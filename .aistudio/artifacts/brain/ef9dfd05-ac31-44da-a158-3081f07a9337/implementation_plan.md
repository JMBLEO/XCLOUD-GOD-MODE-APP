# God Mode Native: Hoja de Ruta Maestra de 20 Fases para Cloud Gaming

Plan arquitectónico integral y modular para el desarrollo del ecosistema **God Mode Native**, unificando el motor nativo de alto rendimiento en Android (Kotlin/C++) con contratos de protocolo compartidos, procesamiento milimétrico de controles con calibración radial, HUD de telemetría translúcido estilo Acrylic Glassmorphism, optimizaciones de red de latencia ultra-baja y soporte multiplataforma.

---

### User Review & Critical Decisions

> [!IMPORTANT]
> **Estrategia de Ejecución Modular (20 Fases)**: La arquitectura se organiza en un núcleo desacoplado de contratos y lógica matemática (`@matrix/*` y adaptadores nativos en Kotlin/Java/C++), permitiendo validar paridad entre plataformas mientras se ejecutan las optimizaciones nativas de hardware directamente en el dispositivo móvil y consola portátil.

- **Confirmado por el usuario**: Hoja de ruta completa de 20 fases que abarca desde la capa base de configuración y mandos hasta perfiles gráficos Vulkan/LSFG, escritorios de consola y canal de distribución.
- **Enfoque de Desarrollo**: Incremental y paso a paso. Cada fase se valida mediante pruebas de compilación y paridad matemática antes de progresar a la siguiente.
- **Aislamiento de Seguridad**: Exclusión estricta de manipulaciones riesgosas del stream o suplantación arbitraria de identidades/IPs, priorizando la aceleración por hardware legítima y el blindaje de la conexión local.

---

### 1. Overview & Core Concept

- **Qué es**: Una suite integral de optimización y control para Xbox Cloud Gaming y Remote Play que combina:
  1. El rendimiento y renderizado nativo con FSR/CAS.
  2. Conceptos ergonómicos de interfaz, estadísticas y atajos de XFly.
  3. Lógica matemática de control con calibración radial y mapeo de perfiles.
  4. Diseño translúcido Acrylic Glassmorphism con monitoreo en tiempo real de FPS, RTT, Jitter y pérdida de paquetes.
- **Audiencia Objetivo**: Jugadores competitivos en dispositivos móviles, tablets, consolas portátiles Android (Odin, Razer Edge, Logitech G Cloud) y Android TV.
- **Beneficio Clave**: Eliminación de zonas muertas axiales defectuosas, cero interrupciones por ahorro de energía mediante bloqueos nativos de Wi-Fi y SoC, y visibilidad analítica continua del stream sin salir del juego.

---

### 2. User Experience & Visual Design

- **Experiencia de Entrada y Calibración en Pantalla**:
  - *Calibración Gráfica de Joysticks*: Visualizador interactivo 2D de stick analógico en tiempo real que dibuja el vector actual $(x, y)$, el círculo de deadzone interior (`innerDeadzone`) y el límite exterior (`outerDeadzone`), permitiendo calibrar la sensibilidad de forma visual.
  - *Overlay In-Game Rápido*: Invocación instantánea mediante el combo físico `View + Menu` (o botón Guía central) sin pausar el flujo de la partida.
  - *Notificaciones Toast de Periféricos*: Avisos compactos translúcidos ante conexión/desconexión de mandos Bluetooth/USB o niveles de batería baja.

- **Diseño Acrylic Glassmorphism**:
  - *Estética*: Paneles flotantes oscuros translúcidos (`#E6121D15` con desenfoque de fondo y borde neón `#4D52E172`).
  - *HUD de Telemetría*: Indicadores de salud de red con estados semánticos:
    - **Óptimo (Verde #52E172)**: RTT < 35ms, Jitter < 3ms, Pérdida = 0%.
    - **Precaución (Amarillo #ECDE38)**: RTT 35-70ms, Jitter 3-10ms, Pérdida < 1.5%.
    - **Crítico (Rojo #E74040)**: RTT > 70ms, Jitter > 10ms o pérdida de paquetes sostenida.

---

### 3. Key Product Decisions & Trade-Offs

- **Decisión 1: Cálculos de Control en Capa Nativa vs. Puente JS**
  - *Estrategia*: Ejecutar la normalización trigonométrica radial directamente en Kotlin (`ControllerInputManager`) y sincronizar con fixtures de paridad.
  - *Razón*: Elimina cualquier sobrecarga de serialización o micro-latencia antes de que la trama viaje por WebRTC.
- **Decisión 2: Persistencia Multinivel (Global ➔ Dispositivo ➔ Por Juego)**
  - *Estrategia*: Almacenamiento síncrono ultrarrápido (equivalente MMKV / SharedPreferences en caché de memoria) indexado por `titleId`.
  - *Razón*: Un shooter competitivo exige deadzone mínima (3-5%) y curvas lineales; un juego de carreras requiere curvas exponenciales más suaves.
- **Decisión 3: Gestión de Energía y Red Dinámica**
  - *Estrategia*: `WifiManager.WIFI_MODE_FULL_LOW_LATENCY` activo únicamente durante streaming, liberándose en pausa/segundo plano.
  - *Razón*: Evita el consumo excesivo de batería cuando la aplicación está en reposo.

---

### 4. Technical Architecture & Roadmap de 20 Fases

```
┌────────────────────────────────────────────────────────────────────────┐
│                        GOD MODE NATIVE SUITE                           │
├────────────────────────────────────────────────────────────────────────┤
│  [UI Layer]                                                            │
│  - Acrylic Glassmorphism Overlay  - Telemetry HUD  - Touch Layouts     │
│  - Joystick Calibration Canvas   - Peripheral Toasts                   │
├────────────────────────────────────────────────────────────────────────┤
│  [Core Processing & State Engine]                                      │
│  - Config Engine (Global / Per-Device / Per-Game titleId)              │
│  - Radial/Axial Deadzones Math   - Button Remapping (A/B, X/Y)         │
│  - View + Menu Latching Combo    - Telemetry Parser & Health Resolver  │
├────────────────────────────────────────────────────────────────────────┤
│  [Hardware & Performance Layer]                                        │
│  - Dual Render Engine Selector (WebView + Native Vulkan / FSR / CAS)   │
│  - Low-Latency WifiLock (Mode 4) - Screen WakeLock                     │
│  - Multi-Channel Gamepad Rumble  - Thermal & Battery Watchdog          │
│  - Selective Cache Detoxification (Preserves Auth & User Configs)      │
└────────────────────────────────────────────────────────────────────────┘
```

#### FASE 1: Fundamentos de Arquitectura y Monorepo
- **Punto 1.1:** Estructuración de contratos compartidos y separación de módulos de plataforma.
- **Punto 1.2:** Diseño de contratos de sesión abstractos para desacoplar los backends WebRTC específicos.
- **Punto 1.3:** Análisis y compatibilidad de licencias de código abierto (AGPL-3.0 / MIT).

#### FASE 2: Motor de Configuración y Políticas de Vídeo
- **Punto 2.1:** Implementación de persistencia síncrona de alta velocidad en Android.
- **Punto 2.2:** Configuración en cascada: Global ➔ Por Dispositivo ➔ Por Juego (`titleId`).
- **Punto 2.3:** Desarrollo de `VideoPolicyResolver` con sanitización de rangos (Brillo, Contraste, Saturación, Nitidez).
- **Punto 2.4:** Gestión inteligente de perfiles dinámicos de ancho de banda (Ahorro de datos vs. Calidad Máxima 1440p).

#### FASE 3: Sistema Avanzado de Entrada (Input y Mandos)
- **Punto 3.1:** Implementación matemática de la Deadzone Radial y Axial con re-escalado $[0, 1]$ y curvas de sensibilidad.
- **Punto 3.2:** Conmutación de layouts de botones (Intercambio A/B y X/Y).
- **Punto 3.3:** Combo de botones `View + Menu` con mecanismo de cerrojo (*latching*) para invocar menús sin pausar.
- **Punto 3.4:** Remapeo global y por hardware configurable por juego.
- **Punto 3.5:** Calibración visual interactiva de joysticks en pantalla.

#### FASE 4: Telemetría y Experiencia de Usuario (HUD Acrylic Glassmorphism)
- **Punto 4.1:** Normalización de métricas de red y decodificación (FPS, RTT, Jitter, Pérdida de paquetes).
- **Punto 4.2:** Estados de salud semánticos del stream (`optimal`, `warning`, `critical`).
- **Punto 4.3:** Interfaz superpuesta translúcida estilo Acrylic Glassmorphism.
- **Punto 4.4:** Sistema de notificaciones *Toast* para estado y batería de periféricos.
- **Punto 4.5:** Registro de diagnóstico de sesión exportable en archivo temporal.

#### FASE 5: Optimización Nativa de Hardware y Rendimiento (Android / Kotlin)
- **Punto 5.1:** Gestión de `WifiManager.WifiLock` (baja latencia) y `PowerManager.WakeLock`.
- **Punto 5.2:** Validación de seguridad e integridad de dependencias externas.
- **Punto 5.3:** Limpieza selectiva de caché multimedia post-sesión respetando credenciales.
- **Punto 5.4:** Monitoreo térmico del SoC de Android y alertas de *thermal throttling*.
- **Punto 5.5:** Selector inteligente de motor de renderizado dual con recuperación de errores.

#### FASE 6: Aseguramiento de Calidad y Pruebas Compartidas
- **Punto 6.1:** Fixtures JSON de prueba compartidos con tolerancia `epsilon`.
- **Punto 6.2:** Pruebas unitarias de regresión en lógica matemática.
- **Punto 6.3:** Validación de paridad matemática con pruebas JUnit en Android.

#### FASE 7: Controles Táctiles Personalizables y Edición de Layouts
- **Punto 7.1:** Motor de diseño para controles virtuales en pantalla.
- **Punto 7.2:** Sistema de reposicionamiento libre (*drag-and-drop*) de botones, D-Pad y sticks.
- **Punto 7.3:** Ocultamiento automático al detectar mando físico activo.
- **Punto 7.4:** Configuración de opacidad, escala y retroalimentación háptica táctil.

#### FASE 8: Optimización de Audio de Baja Latencia y Buffering
- **Punto 8.1:** Ajuste de buffers de audio nativo para baja latencia en WebRTC.
- **Punto 8.2:** Sincronización fina audio-vídeo para prevenir *lip-sync lag*.
- **Punto 8.3:** Control dinámico de muestreo ante fluctuaciones de red.

#### FASE 9: Remapeo Total de Controles y Periféricos Avanzados
- **Punto 9.1:** Interfaz visual para mapeo de mandos Xbox, PlayStation (DualSense) y genéricos.
- **Punto 9.2:** Soporte preliminar para emulación de ratón/teclado a joystick.
- **Punto 9.3:** Rumble multicanal háptico avanzado para mandos y chasis del dispositivo.

#### FASE 10: Adaptación de Escritorio Nativa (Windows / XFly)
- **Punto 10.1:** Modo consola sin barras de navegación en escritorio.
- **Punto 10.2:** Soporte de banderas Chromium para escalado RTX VSR y AMD Video Upscale.
- **Punto 10.3:** Menú rápido flotante `View + Menu` adaptado a escritorio.

#### FASE 11: Soporte Completo para Android TV y Navegación D-Pad
- **Punto 11.1:** Navegación 100% enfocada por D-Pad con indicadores visuales claros.
- **Punto 11.2:** Detección automática del factor de forma (Consola portátil / TV vs. Smartphone).
- **Punto 11.3:** Integración con la interfaz de inicio de Android TV.

#### FASE 12: Tuberías Gráficas Avanzadas y Generación de Fotogramas (Vulkan / LSFG)
- **Punto 12.1:** Procesamiento directo de texturas en memoria gráfica con Vulkan y `AHardwareBuffer`.
- **Punto 12.2:** Verificación legítima de librerías externas de interpolación.
- **Punto 12.3:** Activación condicionada por capacidades de GPU del SoC.

#### FASE 13: Diagnóstico, Telemetría Avanzada y Reporte de Fallos
- **Punto 13.1:** Registrador de eventos de sesión continuo en almacenamiento temporal.
- **Punto 13.2:** Autodiagnóstico de decodificación por hardware de la GPU al iniciar.
- **Punto 13.3:** Exportación anónima de diagnósticos para soporte técnico.

#### FASE 14: Seguridad, Autenticación Robusta y Gestión de Credenciales
- **Punto 14.1:** Endurecimiento del gestor de autenticación y flujos de sesión.
- **Punto 14.2:** Almacenamiento seguro de tokens mediante hardware Keystore de Android.
- **Punto 14.3:** Cierre de sesión seguro con purga de credenciales sin afectar configuraciones locales.

#### FASE 15: Integración Continua (CI/CD) y Pipelines de Compilación
- **Punto 15.1:** Automatización de flujos de compilación y empaquetado.
- **Punto 15.2:** Ejecución automática de pruebas de paridad en cada commit.
- **Punto 15.3:** Generación de APKs de prueba con trazabilidad de versión.

#### FASE 16: Empaquetado, Firma de Código y Distribución
- **Punto 16.1:** Variantes Gradle (`dev`, `prod`, `release`) y optimización ProGuard/R8.
- **Punto 16.2:** Firma criptográfica segura para despliegue.
- **Punto 16.3:** Paquetes optimizados para consolas portátiles y dispositivos móviles.

#### FASE 17: Internacionalización (i18n) y Localización
- **Punto 17.1:** Extracción de recursos de cadenas a formatos estructurados.
- **Punto 17.2:** Soporte multilingüe completo (español, inglés y principales idiomas).
- **Punto 17.3:** Detección de idioma del sistema con anulación manual en ajustes.

#### FASE 18: Auditoría de Rendimiento, Perfiles de Memoria y Fugas (Memory Leaks)
- **Punto 18.1:** Profiling de memoria en vistas de renderizado para sesiones prolongadas.
- **Punto 18.2:** Prevención de pausas por recolección de basura durante el stream.
- **Punto 18.3:** Minimización de la carga de CPU del HUD de telemetría (<1% de consumo).

#### FASE 19: Arquitectura de Extensiones y Soporte Modular de Mods
- **Punto 19.1:** Interfaz de extensiones basada en eventos para filtros y plugins visuales.
- **Punto 19.2:** Carga dinámica de perfiles de modding por juego.

#### FASE 20: Gestión de Lanzamientos, Mantenimiento y Soporte a Largo Plazo
- **Punto 20.1:** Esquema de versionado semántico formal (`SemVer`).
- **Punto 20.2:** Generación automática de notas de versión y trazabilidad de cambios.
- **Punto 20.3:** Sistema de verificación de actualizaciones estables dentro de la aplicación.
