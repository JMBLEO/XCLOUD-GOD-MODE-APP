# Plan de Implementación Revisado: Refinamiento Pro de Streaming, Adaptación de Aspecto, i18n Bilingüe y Extractor Multimedia Xbox

Este plan integra las especificaciones técnicas solicitadas y las 4 mejoras avanzadas de ingeniería (Modo Leanback 10-Foot con Focus Rings, Caché inteligente de texturas, Gráfico WebRTC de FPS/Bitrate con captura de puntero de ratón, y Exportación/Importación de Ajustes en JSON), adaptando dinámicamente la aplicación a cualquier relación de aspecto (teléfono, tablet, TV y consolas portátiles) y asegurando soporte bilingüe real (Español Neutro e Inglés).

---

## Decisiones Críticas y Preferencias Confirmadas

> [!IMPORTANT]
> - **Selección de Idioma**: Detección automática del idioma del sistema con selector reactivo manual en Ajustes para alternar al instante entre **Español Neutro** e **English (US)** sin reiniciar la app.
> - **Escalado Responsivo de Pantalla**: Autoescalado fluido según DPI y aspecto (16:9, 20:9, 16:10, 4:3), con sliders de control fino para tamaño de letra (85%-160%), iconos (80%-150%), portadas (80%-150%) y código QR (80%-140%).
> - **Modo 10-Foot Leanback & Focus Rings**: Detección de mandos físicos (Bluetooth/USB) y teclado para conmutar automáticamente al modo TV/Consola con anillos de enfoque verde Xbox (`#107C10`).
> - **Gestión de Medios Xbox**: Módulo TypeScript `src/services/XboxMultimediaExtractor.ts` y homólogo Kotlin nativo con normalización CDN (`w=1080&h=1620&format=jpg&q=92`) y seguridad TLS 1.3 / HTTPS en los 14 dominios oficiales.

---

## 1. Visión General & Arquitectura del Sistema

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    God Mode Master Streaming Architecture                   │
├──────────────────────────────────────┬──────────────────────────────────────┤
│       Gestor de Idioma i18n          │     Motor de Escalado & Leanback     │
│   (AppStrings: es / en reactivo)     │   (LocalScreenScale / InputDetector) │
│  • Detección automática (Locale.sys) │  • Detección de aspecto y resolución │
│  • Conmutación en caliente           │  • Modo 10-Foot: Focus Rings verdes  │
│  • Consistencia en todas las vistas  │  • Sliders: Letra, Icono, Poster, QR │
├──────────────────────────────────────┴──────────────────────────────────────┤
│                        Experiencia de Usuario Adaptativa                    │
│  ┌──────────────────────┐  ┌──────────────────────┐  ┌───────────────────┐  │
│  │ DualAuthModal (QR)   │  │ Grilla de Juegos     │  │ In-Stream Overlay │  │
│  │ • QR responsivo      │  │ • Caché de texturas  │  │ • FPS / Bitrate   │  │
│  │ • Row en horizontal  │  │ • Pre-carga Posters  │  │ • Captura Ratón   │  │
│  │ • Columna en móvil   │  │ • Chameleon colors   │  │ • FSR 3.1 & HUD   │  │
│  └──────────────────────┘  └──────────────────────┘  └───────────────────┘  │
├─────────────────────────────────────────────────────────────────────────────┤
│                          Servicios & Seguridad de Red                       │
│  • src/services/XboxMultimediaExtractor.ts (14 dominios + Trailers Smooth)  │
│  • src/services/AppStrings.ts & AppStrings.kt (Traducción completa)         │
│  • network_security_config.xml (TLS 1.3 Strict, Bloqueo Cleartext)          │
│  • Backup & Restore Engine (Exportación/Importación de Ajustes en JSON)     │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Experiencia de Usuario & Mejoras de Interfaz

### 2.1 Adaptación al Aspecto de Pantalla & Redimensionamiento del QR
- **Cálculo Dinámico de Contenedor (`BoxWithConstraints`)**:
  - En pantallas horizontales (Tablets, TVs, consolas tipo Steam Deck / Odin 2): Distribución en fila (Row) con código QR a la izquierda y pasos a la derecha, calculando el tamaño del QR con `clamp(min = 160.dp, ideal = maxHeight * 0.52f, max = 280.dp)`.
  - En pantallas verticales (Teléfonos móviles): Distribución apilada en columna con scroll fluido si la densidad de pantalla lo requiere.
  - El usuario puede ajustar el tamaño manualmente mediante el slider en **Ajustes Avanzados → Pantalla & Escala UI**.

### 2.2 Modo 10-Foot Leanback UI y Enfoque Espacial (Focus Rings)
- Detección reactiva de eventos de `KeyEvent` y mandos conectados mediante `InputManager`.
- Cuando se detecta navegación con D-Pad, sticks o teclado, la interfaz resalta el elemento activo con un contorno verde Xbox brillante (`#107C10`) de 2.dp y ligera elevación de escala (`1.04x`), permitiendo navegación 100% libre de interacción táctil.

### 2.3 Soporte Bilingüe Completo (Español Neutro & English)
- **Estructura unificada de localización**:
  - Implementación en `src/services/AppStrings.ts` (TypeScript) y `com.aistudio.xcloudgodmode.ai.ui.theme.AppStrings.kt` (Kotlin/Compose).
  - Selector en **Ajustes Avanzados**:
    - `Sistema (Automático)`
    - `Español (Latinoamérica / Neutro)`
    - `English (United States)`
  - Recomposición en tiempo real de todos los textos: navegación, catálogo, ficha técnica del juego (compatibilidad, idiomas, ESRB), modal de autenticación y menús in-stream.

### 2.4 Optimizaciones In-Stream Avanzadas
- **Lectura en tiempo real de FPS y Bitrate WebRTC**:
  - Muestra tasa de cuadros real por segundo (FPS) y ancho de banda recibido (Kbps/Mbps).
  - Gráfico minimalista de fluctuación de latencia tipo osciloscopio en el HUD.
- **Captura de Teclado y Ratón (MKB Mode)**:
  - Soporte de captura de puntero en títulos compatibles (ej. *Age of Empires II*, *Halo Infinite*).

---

## 3. Servicios, Rendimiento y Seguridad

### 3.1 Módulo `XboxMultimediaExtractor` (TypeScript & Kotlin)
- Soporte exhaustivo para los 14 dominios oficiales de Xbox:
  1. `emerald.xboxservices.com`
  2. `contentaccess.exp.xboxservices.com`
  3. `userpresence.xboxlive.com`
  4. `assets.play.xbox.com`
  5. `notificationinbox.xboxlive.com`
  6. `xblmessaging.xboxlive.com`
  7. `rta.xboxlive.com`
  8. `clc.core.gssv-play-prod.xboxlive.com`
  9. `peoplehub.xboxlive.com`
  10. `chat.xboxlive.com`
  11. `accounts.xboxlive.com`
  12. `uhf.microsoft.com`
  13. `store-images.s-microsoft.com`
  14. `store-images.microsoft.com`
- Función `normalizeUrl(rawUrl, width, height)` con fallback a `w=1080&h=1620&format=jpg&q=92` y normalización de esquemas `//`.
- Extracción de trailers Smooth Streaming (`.ism/manifest`), clasificación de edad con insignias oficiales y extracción de paleta de colores para el motor Chameleon.

### 3.2 Caché Inteligente de Activos (Texturas y Portadas)
- Configuración de caché en memoria LruCache y disco para las carátulas, eliminando parpadeos y retrasos de red durante el scroll rápido en catálogo.

### 3.3 Exportación e Importación de Ajustes en JSON
- Funcionalidad en **Ajustes Avanzados**:
  - **Exportar Ajustes**: Genera un archivo o cadena JSON con la configuración completa del motor (servidores, bypass, códecs, escala, idioma y sliders).
  - **Importar Ajustes**: Restaura y aplica instantáneamente el perfil de configuración sin reiniciar.

### 3.4 Endurecimiento de Seguridad de Red (`network_security_config.xml`)
- Configuración estricta de `domain-config` con `cleartextTrafficPermitted="false"` para todos los dominios `xboxservices.com`, `xboxlive.com`, `microsoft.com` y CDNs `store-images.*`.

---

## 4. Fases de Ejecución

1. **Internacionalización**: Crear `src/services/AppStrings.ts` y `AppStrings.kt`, integrando el estado de idioma en `SettingsModel` y todas las vistas.
2. **Escalado Responsivo y Modo Leanback**: Adaptar `DualAuthModal.kt` para escalado dinámico de QR según relación de aspecto y añadir soporte para Focus Rings.
3. **Servicio Multimedia**: Implementar `src/services/XboxMultimediaExtractor.ts` y enriquecer el extractor Kotlin con normalización y caché de imágenes.
4. **In-Stream & Backup JSON**: Añadir lectura de FPS/Bitrate en el HUD de `StreamScreen.kt` y la opción de exportar/importar ajustes JSON.
5. **Seguridad de Red & Verificación**: Verificar `network_security_config.xml` y compilar el proyecto con `compile_applet`.
