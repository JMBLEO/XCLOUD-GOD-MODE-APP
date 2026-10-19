# XCloud God Mode AI - Native Client Transition

Transition the application from a WebView-based wrapper to a **full native Xbox Cloud Gaming client**. This involves implementing native WebRTC streaming (separating signaling from multimedia), a robust authentication flow (XAL/SISU) with native ECDSA signatures, and deep AI integration for performance optimization.

### Confirmed Decisions & User Feedback

> [!IMPORTANT]
> The following decisions were confirmed by the user in Phase 1:
> - **Primary Focus**: Stable Native Streaming (WebRTC + MediaCodec) and Full Native Auth Flow (XAL / SISU / SISU 2).
> - **Visual Aesthetic**: Hybrid approach incorporating Xbox Dashboard, Clean Material 3, and Cyberpunk elements.
> - **Performance**: Prioritizing LSFG (Frame Generation), Custom Shaders, and ultra-low latency.

- **Confirmed Decision 1**: Use native `OkHttp` for all API signaling with `JoseCripto` for ECDSA P-256 header signing.
- **Confirmed Decision 2**: Implement native WebRTC transport to eliminate the overhead of Chromium's stack in WebView.
- **Confirmed Decision 3**: Integrate Gemini 3.1 Flash for real-time telemetry analysis and Gemini 4 Argon (Thinking) for deep strategy/optimization.

---

### 1. Overview & Core Concept

**XCloud God Mode AI** aims to be the ultimate native client for Xbox Cloud Gaming on Android. By bypassing the browser-based `xbox.com/play` interface, it achieves lower latency, better control mapping, and unique features like frame interpolation (LSFG) and AI-driven stream tuning.

**Target Audience**: Power users and competitive gamers who require the absolute lowest latency and highest visual quality from xCloud.

---

### 2. User Experience & Visual Design

**Key User Flows**:
1. **Native Onboarding**: A high-polish login flow using SISU/XAL that handles Microsoft authentication without 403 errors.
2. **Game Catalog**: A native browsing experience (Xbox Dashboard style) with AI-recommended titles.
3. **Immersive Streaming**: A full-screen video layer with a floating "God Mode" HUD for telemetry and a sidebar for AI Copilot.
4. **Optimization Center**: A dedicated screen to toggle between "Battery Saver", "Balanced", and "God Mode" profiles, with custom shaders and refresh rate adjustments.

**Visual Identity**:
- **Aesthetic**: Dark, neon-accented UI (Cyberpunk) blended with the clean structure of Material 3 and the iconic green of Xbox.
- **Color Palette**: `#107C10` (Xbox Green), `#000000` (Deep Black), `#52E172` (God Mode Glow), and Cyberpunk purple/cyan accents for AI features.
- **Interactive Feedback**: Haptic pulses on button presses, fluid transitions between dashboard and stream, and a "Matrix-style" telemetry HUD.

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: Native WebRTC vs WebView**
    - *Chosen Approach*: Full Native WebRTC implementation using `google-webrtc`.
    - *Why*: Eliminates the "middleman" of the browser engine, allowing direct control over video buffers, jitter buffers, and `DataChannel` for input.
- **Decision 2: Authentication Strategy**
    - *Chosen Approach*: Native SISU flow with ECDSA signatures.
    - *Why*: The web-based login often hits 403 errors or device-check failures on mobile browsers. Native signatures (XAL) are more resilient and professional.
- **Decision 3: AI Model Usage**
    - *Chosen Approach*: Multi-model approach (Flash for speed, Argon for depth).
    - *Why*: Telemetry diagnosis needs to be near-instant, while game strategy analysis can benefit from longer "thinking" times.

---

### 4. Technical Architecture & Data Strategy

```unicode
┌─────────────────────────────────────────────────────────────┐
│                    MainActivity (Host)                      │
│  ┌───────────────────────────────────────────────────────┐  │
│  │                UI LAYER (Jetpack Compose)             │  │
│  │  ┌──────────────┐   ┌──────────────┐   ┌───────────┐  │  │
│  │  │ Game Catalog │   │ Stream View  │   │ AI Overlay│  │  │
│  │  └──────────────┘   └──────────────┘   └───────────┘  │  │
│  └───────────────────────────┬───────────────────────────┘  │
│                              │                              │
│  ┌───────────────────────────▼───────────────────────────┐  │
│  │                 SERVICES & CORE LOGIC                 │  │
│  │  ┌─────────────────┐        ┌──────────────────────┐  │  │
│  │  │ XboxAuthManager │        │ WebRTCStreamingSvc   │  │  │
│  │  │ (XAL/SISU Flow) │        │ (Multimedia Engine)  │  │  │
│  │  └────────┬────────┘        └──────────┬───────────┘  │  │
│  │           │                            │              │  │
│  │  ┌────────▼────────┐        ┌──────────▼──────────┐  │  │
│  │  │ NetworkClient   │        │ MediaCodec Decoder  │  │  │
│  │  │ (Sig Injection) │        │ (AHardwareBuffer)   │  │  │
│  │  └─────────────────┘        └─────────────────────┘  │  │
│  └───────────────────────────┬───────────────────────────┘  │
│                              │                              │
│  ┌───────────────────────────▼───────────────────────────┐  │
│  │                 INTEGRATIONS & PERSISTENCE            │  │
│  │  ┌─────────────────┐        ┌──────────────────────┐  │  │
│  │  │ Gemini API      │        │ Firestore / Room     │  │  │
│  │  │ (Optimization)  │        │ (Game Profiles)      │  │  │
│  │  └─────────────────┘        └──────────────────────┘  │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

**Data Model**:
- `XboxSession`: Stores tokens (XSTS, GSToken, Region URI) and user info.
- `OptimizationProfile`: Encapsulates shader settings, bitrates, and performance targets.

**Key Sequences**:
1. **Auth Sequence**: `getDeviceToken` -> `createLoginSession` -> `exchangeMsaCode` -> `authorizeSisu` -> `getGssvToken`.
2. **Stream Sequence**: `GSSV Signaling (SDP/ICE)` -> `WebRTC Connection` -> `MediaCodec Surface Render` -> `Input DataChannel Binding`.
