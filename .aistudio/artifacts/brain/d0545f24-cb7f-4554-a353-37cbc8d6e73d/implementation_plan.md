# Implementation Plan - PlayStaxBox Native Experience (Revised)

Implement a fully immersive console-like experience using Native Kotlin and Jetpack Compose, featuring procedural audio-haptic synthesis, QR-based login, and advanced UI animations.

## 1. Procedural Audio-Haptic Engine (`PlayStaxBoxNativeSoundEngine.kt`)
*   **PCM Synthesis & Caching**: Low-latency engine using `AudioTrack` with a pre-rendered `pcmCache` for 0ms navigation sounds (Focus, Tab, Select).
*   **Signatures**: `playstax_hybrid`, `ps5_crystalline`, `xbox_blades_360`, and `ps3_xmb_click`.
*   **Audio-Haptic Coupling**: Synchronize micro-pulses of vibration (8ms - 35ms) with UI sounds for tactical feedback.
*   **Stream Audio Policy**: Implement `ActiveInputLayer` state to manage sound silencing during gameplay vs. overlay navigation.

## 2. Secure Login & QR Visuals (`Auth Module`)
*   **RPS Auth Path**: Update `XboxFullAuthManager.kt` to use `AuthMethod: "RPS"` and direct OAuth2 token exchange to completely bypass HTTP 403 blocks.
*   **QR Visual Simulation**: Add a stylized SVG/Grid-based QR code representation in `DualAuthModal.kt` to mimic Smart TV/Pro app experiences.
*   **Bridge Integration**: Expose `playNativeUiSound` and `setStreamLayerState` to the WebView via `GodModeAndroidBridge`.

## 3. Dynamic UI & Animations (`PlayStaxBoxComponents.kt`)
*   **PS3 XMB Ribbon Wave**: Smooth sine wave background using Compose `Canvas` with Chameleon color modulation.
*   **PS5 Ken Burns Hero**: Hero art with parallax scale/pan animations and resilient Multi-CDN loading (Steam 2x -> PSN -> Epic -> Xbox).
*   **Chameleon Engine**: Real-time StatusBar and NavigationBar color interpolation matching the active game art.

## 4. Data & Logic Upgrades
*   **Settings Updates**: Expand `SettingsModel` with sound themes, haptic coupling toggles, and server lock configurations.
*   **Live Steam Search**: Implement real-time storesearch API calls in `CatalogRepository` to fetch UHD art during library browsing.
*   **Navigation Hooks**: Connect Focus, Tab change, and Action events in `HomeScreen.kt` to the sound engine.
