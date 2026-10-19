// ==UserScript==
// @name         XCloud (God Mode AI) - Native Suite Edition
// @namespace    https://github.com/JMBLEO/XCLOUD-GOD-MODE-AI
// @version      2.0
// @description  Script maestro v2.0: Puente Nativo Android/Windows/webOS, HUD en capa prioritaria inmune a reescalado, Auto-Detox híbrido (JS + Nativo), Forzado 1440p/4K/8K en Cloud y XRemote, Shaders IA (Real-ESRGAN & SeedVR2), Motor Real de Zonas Muertas/Polling 4ms/Swap ABXY/Emulación M&K/Controles Táctiles Universales y UI Windows 11 Glassmorphism.
// @author       JMBLEO
// @license      MIT
// @match        https://www.xbox.com/*/*play*
// @match        https://www.xbox.com/*/play*
// @run-at       document-start
// @grant        none
// @updateURL    https://github.com/JMBLEO/XCLOUD-GOD-MODE-AI/releases/latest/download/xcloud-god-mode-ai.user.js
// @downloadURL  https://github.com/JMBLEO/XCLOUD-GOD-MODE-AI/releases/latest/download/xcloud-god-mode-ai.user.js
// ==/UserScript==

(function () {
    'use strict';

    // Evitar doble inyección si el WebView dispara múltiples cargas de marco
    if (window.__GOD_MODE_AI_INITIALIZED__) return;
    window.__GOD_MODE_AI_INITIALIZED__ = true;

    // ==========================================
    // 0. PUENTE NATIVO MULTIPLATAFORMA Y DETECCIÓN DE HARDWARE
    // ==========================================
    const GodModeNativeBridge = {
        getPlatform: function () {
            if (typeof window.GodModeAndroid !== 'undefined') return 'android';
            if (window.chrome && window.chrome.webview) return 'windows';
            if (typeof window.webOS !== 'undefined') return 'webos';
            return 'browser';
        },
        syncConfig: function (cfg) {
            try {
                const payload = JSON.stringify(cfg);
                if (typeof window.GodModeAndroid !== 'undefined' && window.GodModeAndroid.updateNativeConfig) {
                    window.GodModeAndroid.updateNativeConfig(payload);
                } else if (window.chrome && window.chrome.webview) {
                    window.chrome.webview.postMessage({ type: 'SYNC_CONFIG', payload: cfg });
                }
            } catch (e) {}
        },
        vibrate: function (durationMs, weakMag, strongMag) {
            try {
                if (typeof window.GodModeAndroid !== 'undefined' && window.GodModeAndroid.vibrateController) {
                    window.GodModeAndroid.vibrateController(
                        Math.round(durationMs || 100),
                        Math.round(Math.min(1.0, Math.max(0, weakMag || 0)) * 255),
                        Math.round(Math.min(1.0, Math.max(0, strongMag || 0)) * 255)
                    );
                    return true;
                } else if (window.chrome && window.chrome.webview) {
                    window.chrome.webview.postMessage({
                        type: 'RUMBLE',
                        duration: Math.round(durationMs || 100),
                        weak: weakMag || 0,
                        strong: strongMag || 0
                    });
                    return true;
                }
            } catch (e) {}
            return false;
        },
        clearNativeCache: function () {
            try {
                if (typeof window.GodModeAndroid !== 'undefined' && window.GodModeAndroid.clearNativeMediaCache) {
                    window.GodModeAndroid.clearNativeMediaCache();
                } else if (window.chrome && window.chrome.webview) {
                    window.chrome.webview.postMessage({ type: 'CLEAR_MEDIA_CACHE' });
                }
            } catch (e) {}
        },
        setPointerCapture: function (enable) {
            try {
                if (typeof window.GodModeAndroid !== 'undefined' && window.GodModeAndroid.setPointerCapture) {
                    window.GodModeAndroid.setPointerCapture(!!enable);
                }
            } catch (e) {}
        },
        setRefreshRate: function (unlock120) {
            try {
                if (typeof window.GodModeAndroid !== 'undefined' && window.GodModeAndroid.setPreferredRefreshRate) {
                    window.GodModeAndroid.setPreferredRefreshRate(unlock120 ? 120.0 : 60.0);
                }
            } catch (e) {}
        },
        openAiCopilot: function () {
            try {
                if (typeof window.GodModeAndroid !== 'undefined' && window.GodModeAndroid.openAiCopilot) {
                    window.GodModeAndroid.openAiCopilot();
                }
            } catch (e) {}
        },
        openXboxLogin: function () {
            try {
                if (typeof window.GodModeAndroid !== 'undefined' && window.GodModeAndroid.openXboxLogin) {
                    window.GodModeAndroid.openXboxLogin();
                }
            } catch (e) {}
        },
        updatePerformanceMetrics: function (fps, lat, jit, loss, btr, dt) {
            try {
                if (typeof window.GodModeAndroid !== 'undefined' && window.GodModeAndroid.updatePerformanceMetrics) {
                    window.GodModeAndroid.updatePerformanceMetrics(fps, lat, jit, loss, btr, dt);
                }
            } catch (e) {}
        },
        setCurrentGame: function (title) {
            try {
                if (typeof window.GodModeAndroid !== 'undefined' && window.GodModeAndroid.setCurrentGame) {
                    window.GodModeAndroid.setCurrentGame(title);
                }
            } catch (e) {}
        },
        openOptimizationSettings: function () {
            try {
                if (typeof window.GodModeAndroid !== 'undefined' && window.GodModeAndroid.openOptimizationSettings) {
                    window.GodModeAndroid.openOptimizationSettings();
                }
            } catch (e) {}
        }
    };
    window.GodModeNativeBridge = GodModeNativeBridge;

    const isEn = navigator.language.startsWith('en');
    const t = (es, en) => isEn ? en : es;
    const q = (es, en) => `<span title="${t(es, en)}" style="cursor:help; margin-left: 6px; font-size: 14px; filter: drop-shadow(0px 0px 2px rgba(255,255,255,0.4));">❔</span>`;

    // Captura de pantalla física real antes de aplicar el Spoofing
    const REAL_DPR = window.devicePixelRatio || 1;
    const REAL_PHYSICAL_W = Math.round(window.screen.width * REAL_DPR);
    const REAL_PHYSICAL_H = Math.round(window.screen.height * REAL_DPR);
    const isHigherThanFHD = REAL_PHYSICAL_W > 1920 || REAL_PHYSICAL_H > 1080;

    const isHdrSupported = window.matchMedia('(dynamic-range: high)').matches;
    let browserHasAV1 = false, browserHasHEVC = false, browserHasVP9 = false;
    try {
        const codecs = RTCRtpReceiver.getCapabilities('video').codecs;
        browserHasAV1 = codecs.some(c => c.mimeType.toLowerCase().includes('av1'));
        browserHasHEVC = codecs.some(c => c.mimeType.toLowerCase().includes('h265') || c.mimeType.toLowerCase().includes('hevc'));
        browserHasVP9 = codecs.some(c => c.mimeType.toLowerCase().includes('vp9'));
    } catch (e) {}

    // ==========================================
    // 1. SISTEMA DE NOTIFICACIONES (TOAST / POP-UPS GLASSMORPHISM)
    // ==========================================
    const Toast = {
        show: function (message, icon = '🎮', duration = 4200) {
            if (!document.body) {
                window.addEventListener('DOMContentLoaded', () => Toast.show(message, icon, duration), { once: true });
                return;
            }
            let container = document.getElementById('mjs-toast-container');
            const activeRoot = document.fullscreenElement || document.body;
            if (!container) {
                container = document.createElement('div');
                container.id = 'mjs-toast-container';
                activeRoot.appendChild(container);

                const style = document.createElement('style');
                style.innerHTML = `
                    #mjs-toast-container { position: fixed; top: 20px; left: 50%; transform: translate3d(-50%, 0, 9999px); z-index: 2147483647 !important; display: flex; flex-direction: column; gap: 8px; pointer-events: none; isolation: isolate; }
                    .mjs-toast { 
                        background: rgba(20, 20, 20, 0.45) !important; 
                        backdrop-filter: blur(16px) saturate(180%) !important; 
                        -webkit-backdrop-filter: blur(16px) saturate(180%) !important; 
                        border: 1px solid rgba(255, 255, 255, 0.18) !important; 
                        color: #fff; border-radius: 10px; padding: 10px 22px; font-family: 'Segoe UI', sans-serif; font-size: 13.5px; font-weight: 600; box-shadow: 0 8px 32px 0 rgba(0,0,0,0.45), inset 0 1px 0 rgba(255,255,255,0.2); display: flex; align-items: center; gap: 10px; opacity: 0; transform: translateY(-20px); transition: all 0.3s ease; text-shadow: 0 1px 2px rgba(0,0,0,0.8);
                    }
                    .mjs-toast.show { opacity: 1; transform: translateY(0); }
                `;
                document.head.appendChild(style);
            } else if (container.parentElement !== activeRoot) {
                activeRoot.appendChild(container);
            }

            const toast = document.createElement('div');
            toast.className = 'mjs-toast';
            toast.innerHTML = `<span style="font-size: 18px;">${icon}</span> <span>${message}</span>`;
            container.appendChild(toast);

            setTimeout(() => toast.classList.add('show'), 10);
            setTimeout(() => {
                toast.classList.remove('show');
                setTimeout(() => toast.remove(), 300);
            }, duration);
        }
    };
    window.GodModeToast = Toast;

    function detectDeviceName(id) {
        const lowerId = (id || '').toLowerCase();
        if (lowerId.includes('xbox') || lowerId.includes('xinput')) return { name: 'Control Xbox', icon: '🎮' };
        if (lowerId.includes('dualsense') || lowerId.includes('dualshock') || lowerId.includes('playstation') || lowerId.includes('wireless controller')) return { name: 'Control PlayStation', icon: '🎮' };
        if (lowerId.includes('nintendo') || lowerId.includes('pro controller') || lowerId.includes('joy-con')) return { name: 'Control Nintendo', icon: '🎮' };
        if (lowerId.includes('wheel') || lowerId.includes('steering')) return { name: 'Volante de Auto', icon: '🏎️' };
        if (lowerId.includes('flight') || lowerId.includes('hotas') || lowerId.includes('yoke')) return { name: 'Mando de Avión', icon: '✈️' };
        if (lowerId.includes('remote')) return { name: 'Control Remoto', icon: '📱' };
        return { name: 'Mando Genérico / Otro', icon: '🕹️' };
    }

    window.addEventListener('gamepadconnected', (e) => {
        const device = detectDeviceName(e.gamepad.id);
        Toast.show(`${device.name} conectado`, device.icon);
    });

    window.addEventListener('gamepaddisconnected', (e) => {
        const device = detectDeviceName(e.gamepad.id);
        Toast.show(`${device.name} desconectado`, '🔌');
    });

    // ==========================================
    // 2. CONFIGURACIÓN BASE UNIFICADA
    // ==========================================
    const DEFAULT_CONFIG = {
        enableNetworkBypass: false, bypassIp: 'Desactivado', customIp: '', storeLocale: '',
        enableServerLock: false, forceServer: 'Automático', preferIPv6: false, disableNetworkCheck: true,
        aiReframing: true, infiniteBitrate: true, forceSeriesX: true, unlock120Fps: true,
        enableCodecOverride: false, codecPref: '🌟 Auto-Detect Máximo Soportado', hdrMode: 'off', lowLatencyMode: true,
        ultraResolution: true, force4K: false, force8K: false, targetResProfile: '1440p', forceXRemoteRes: true, preventResDrops: true,
        fsrCasMode: 'esrgan_srvgg', seedvrColorFix: true,
        enable51Audio: true, enableMic: false, volBooster: false, combineAV: false, muteOnBlur: false,

        enableKeyboardNative: false, forceKeyboardConsole: false, enableMnKEmulation: false,
        enableMouseNative: false, safeNativeModeOnly: false,
        enableTouchNative: true, forceTouchVirtual: false,
        enableCoOp: false, swapABXY: false,

        pollingRate: 4, deadzoneL: 0, deadzoneR: 0,
        forceVib: true, vibProfile: 'standard', vibIntensity: 100,

        autoFullscreen: false, hideIdleMouse: true, touchSlideHide: false, autoHideTouch: true,
        videoStretch: 'normal', stretchX: 1.00, stretchY: 1.00, aspectRatio: '16:9',
        vidBrightness: 100, vidContrast: 100, vidSaturation: 100, vidSharpness: 4,
        antiKick: true, blockAnalytics: true, skipSplash: true, skipFeedback: true, disableUIAnim: false,
        smartTvMode: false, autoDetoxCache: true,
        menuOpacity: 95, showStats: false, statsPosition: 'top-center', statsOpacity: 85, osdLayout: 'horizontal',
        osdTime: true, osdPlay: true, osdBatt: true, osdPing: true, osdJit: true, osdRes: true, osdFps: true,
        osdBtr: true, osdDt: true, osdPl: true, osdFl: true, osdDl: false, osdUl: false, osdNet: true
    };

    let config = {};
    try {
        config = JSON.parse(localStorage.getItem('GodModeUltimateConfig')) || {};
    } catch (e) {
        config = {};
    }
    for (let key in DEFAULT_CONFIG) {
        if (config[key] === undefined) config[key] = DEFAULT_CONFIG[key];
    }

    function saveConfig() {
        try {
            localStorage.setItem('GodModeUltimateConfig', JSON.stringify(config));
            GodModeNativeBridge.syncConfig(config);
            GodModeNativeBridge.setRefreshRate(config.unlock120Fps);
        } catch (e) {}
    }

    // Sincronización inicial inmediata con la capa nativa de Android
    GodModeNativeBridge.syncConfig(config);
    GodModeNativeBridge.setRefreshRate(config.unlock120Fps);

    let kbDetected = false;
    window.addEventListener('keydown', () => {
        if (!kbDetected && (config.enableKeyboardNative || config.forceKeyboardConsole)) {
            Toast.show('Teclado detectado y vinculado', '⌨️');
            kbDetected = true;
        }
    });

    let mouseDetected = false;
    window.addEventListener('mousemove', () => {
        if (!mouseDetected && (config.enableMouseNative || config.forceKeyboardConsole)) {
            Toast.show('Ratón detectado y vinculado', '🖱️');
            mouseDetected = true;
        }
    });

    // ==========================================
    // 2.5 SISTEMA AUTO-DETOX POST-JUEGO (LIMPIEZA SELECTIVA JS + NATIVA)
    // ==========================================
    let wasInGameSession = false;
    let lastDetoxTime = 0;

    async function runPostGameCacheDetox(isManual = false) {
        if (!isManual && !config.autoDetoxCache) return;
        const now = Date.now();
        if (!isManual && (now - lastDetoxTime < 5000)) return;
        lastDetoxTime = now;

        Toast.show('Iniciando Detox post-juego: Liberando caché de imágenes, video y fuentes...', '🧹', 3500);

        let deletedAssetsCount = 0;

        // 1. Limpieza selectiva en Cache Storage API
        if ('caches' in window) {
            try {
                const cacheNames = await window.caches.keys();
                const mediaRegex = /\.(png|jpe?g|webp|gif|svg|avif|mp4|webm|m4s|mp3|wav|ogg|woff2?|ttf|otf|eot|css)(\?.*)?$/i;
                const heavyUrlKeywords = ['images-eds', 'store-images', 'assets', 'catalog', 'telemetry', 'video', 'media', 'font', 'art'];

                for (const name of cacheNames) {
                    const cache = await window.caches.open(name);
                    const requests = await cache.keys();
                    for (const req of requests) {
                        const url = req.url.toLowerCase();
                        if (mediaRegex.test(url) || heavyUrlKeywords.some(k => url.includes(k))) {
                            await cache.delete(req);
                            deletedAssetsCount++;
                        }
                    }
                }
            } catch (e) {}
        }

        // 2. Limpieza de buffers de rendimiento y red del navegador
        try {
            if (window.performance) {
                if (performance.clearResourceTimings) performance.clearResourceTimings();
                if (performance.clearMarks) performance.clearMarks();
                if (performance.clearMeasures) performance.clearMeasures();
            }
        } catch (e) {}

        // 3. Liberación de buffers de elementos <video> residuales y texturas pesadas fuera de vista
        try {
            document.querySelectorAll('video').forEach(v => {
                if (v.paused || v.ended || !v.closest('#game-stream')) {
                    if (!v.closest('#game-stream')) {
                        v.pause();
                        v.removeAttribute('src');
                        v.load();
                    }
                }
            });
            document.querySelectorAll('img').forEach(img => {
                img.decoding = 'async';
                img.loading = 'lazy';
            });
        } catch (e) {}

        // 4. Purga selectiva de telemetría/logs pesados en sessionStorage (Respetando Auth, MSAL, Xbox Live y GodModeUltimateConfig)
        try {
            const safeKeepPatterns = ['msal', 'xbl', 'auth', 'token', 'user', 'session', 'godmodeultimateconfig', 'streamresolution', 'xboxcloudgaming', 'settings', 'preferred', 'anon'];
            for (let i = sessionStorage.length - 1; i >= 0; i--) {
                const k = sessionStorage.key(i);
                if (k) {
                    const lk = k.toLowerCase();
                    const isProtected = safeKeepPatterns.some(p => lk.includes(p));
                    if (!isProtected && (lk.includes('telemetry') || lk.includes('qos') || lk.includes('log') || lk.includes('cache') || lk.includes('catalog') || lk.includes('correlation'))) {
                        sessionStorage.removeItem(k);
                        deletedAssetsCount++;
                    }
                }
            }
        } catch (e) {}

        // 5. Ejecutar limpieza de caché de medios en la capa nativa (Android WebView / Windows WebView2)
        GodModeNativeBridge.clearNativeCache();

        setTimeout(() => {
            Toast.show(`Detox completado: Caché multimedia y buffers liberados (${deletedAssetsCount} bloques) • Sesión y Ajustes intactos`, '✨', 5000);
        }, 900);
    }

    // ==========================================
    // 3. DICCIONARIOS MASIVOS COMPLETOS
    // ==========================================
    const REGION_IPS = {
        [t('Desactivado', 'Disabled')]: '',
        '🇦🇺 [MS Azure] Australia East (New South Wales)': '20.53.111.128',
        '🇦🇺 [MS Azure] Australia South East (Victoria)': '20.193.18.128',
        '🇧🇷 [MS Azure] Brazil South (São Paulo State)': '169.150.198.66',
        '🇨🇦 [MS Azure] Canada Central (Toronto)': '20.48.224.128',
        '🇨🇦 [MS Azure] Canada East (Quebec City)': '52.229.123.128',
        '🇨🇱 [MS Azure] Chile Central (Santiago - Quilicura)': '20.143.92.1',
        '🇨🇴 [MS Azure] Colombia (Bogotá Edge)': '186.80.12.1',
        '🇫🇷 [MS Azure] France Central (Paris)': '20.190.155.128',
        '🇫🇷 [MS Azure] France South (Marseille)': '40.66.57.128',
        '🇩🇪 [MS Azure] Germany North (Bremen)': '51.116.146.128',
        '🇩🇪 [MS Azure] Germany West Central (Frankfurt)': '51.116.57.128',
        '🇮🇳 [MS Azure] Central India (Pune)': '20.192.115.128',
        '🇮🇳 [MS Azure] South India (Chennai)': '20.192.203.128',
        '🇯🇵 [MS Azure] Japan East (Tokyo, Saitama)': '210.131.113.123',
        '🇯🇵 [MS Azure] Japan West (Osaka)': '20.43.149.128',
        '🇰🇷 [MS Azure] Korea Central (Seoul)': '168.126.63.1',
        '🇰🇷 [MS Azure] Korea South (Busan)': '20.194.240.128',
        '🇲🇽 [MS Azure] Mexico Central (Querétaro State)': '20.195.68.1',
        '🇳🇱 [MS Azure] West Europe (North Holland)': '20.31.25.128',
        '🇳🇴 [MS Azure] Norway East (Oslo)': '51.120.219.128',
        '🇳🇴 [MS Azure] Norway West (Stavanger)': '51.120.106.128',
        '🇵🇱 [MS Azure] Poland Central (Warsaw)': '45.134.212.66',
        '🇿🇦 [MS Azure] South Africa North (Johannesburg)': '102.133.155.128',
        '🇿🇦 [MS Azure] South Africa West (Cape Town)': '102.133.242.128',
        '🇸🇪 [MS Azure] Sweden Central (Gävle)': '51.12.213.128',
        '🇨🇭 [MS Azure] Switzerland North (Zurich)': '51.107.57.128',
        '🇨🇭 [MS Azure] Switzerland West (Geneva)': '51.107.169.128',
        '🇦🇪 [MS Azure] UAE Central (Abu Dhabi)': '20.195.111.128',
        '🇦🇪 [MS Azure] UAE North (Dubai)': '20.195.148.128',
        '🇬🇧 [MS Azure] UK South (London)': '20.33.26.128',
        '🇬🇧 [MS Azure] UK West (Cardiff)': '51.140.231.128',
        '🇺🇸 [MS Azure] Central US (Iowa)': '52.173.18.128',
        '🇺🇸 [MS Azure] East US (Virginia)': '20.42.73.128',
        '🇺🇸 [MS Azure] East US 2 (Virginia)': '20.39.81.128',
        '🇺🇸 [MS Azure] East US 3 (Georgia)': '20.50.81.128',
        '🇺🇸 [MS Azure] North Central US (Illinois)': '20.49.106.128',
        '🇺🇸 [MS Azure] South Central US (Texas)': '20.45.18.128',
        '🇺🇸 [MS Azure] West Central US (Wyoming)': '52.247.213.128',
        '🇺🇸 [MS Azure] West US (California)': '13.89.204.1',
        '🇺🇸 [MS Azure] West US 2 (Washington)': '20.46.81.128',
        '🇺🇸 [MS Azure] West US 3 (Arizona)': '20.150.81.128',
        '🇨🇱 [MS Azure] Chile Edge (Santiago Pasarela)': '151.206.123.10',
        '🇨🇱 [GCP] Google Cloud Chile (Santiago Node)': '34.176.0.1',
        '🇨🇱 [AWS] Amazon Web Services Chile (Santiago Edge)': '15.228.0.1',
        '🇨🇱 [Cloudflare] Chile Edge Node (Santiago)': '104.18.0.1',
        '🇨🇱 [ISP] Entel Chile (Fibra Nacional)': '200.83.128.1',
        '🇨🇱 [ISP] VTR / Claro Chile (Enlace Metropolitano)': '190.160.0.1',
        '🇨🇱 [ISP] GTD Manquehue (Baja Latencia Stgo)': '200.72.0.1',
        '🇨🇱 [ISP] Telefónica / Movistar Chile': '200.75.0.1',
        '🇨🇱 [ISP] Wom Chile (Enlace de Datos)': '181.42.0.1',
        '🇦🇷 [MS Azure] Argentina - Mendoza / Cuyo (Enlace Transandino)': '190.221.180.1',
        '🇦🇷 [GCP] Google Cloud Argentina (Buenos Aires Node)': '34.175.0.1',
        '🇦🇷 [ISP] Movistar Argentina (Baires)': '181.114.212.34',
        '🇦🇷 [ISP] Claro Argentina (Nodos de Enlace)': '190.111.246.128',
        '🇦🇷 [ISP] Telecentro (Cuyo / Baires)': '190.221.32.1',
        '🇦🇷 [ISP] Arsat (Red Troncal Nacional)': '200.51.240.1',
        '🇧🇷 [GCP] Google Cloud Brasil (São Paulo)': '34.151.0.1',
        '🇧🇷 [AWS] Amazon Web Services Brasil (São Paulo)': '18.228.0.1',
        '🇧🇷 [ISP] Claro BR (São Paulo Edge)': '177.54.148.163',
        '🇧🇷 [ISP] Vivo BR (São Paulo Backbone)': '177.10.128.1',
        '🇺🇸 [MS Azure] EE.UU. (4.2.2.2 - Global DNS/Opt)': '4.2.2.2',
        '🇺🇸 [AWS] EE.UU. East (Virginia)': '3.80.0.1',
        '🇪🇺 [MS Azure] Europa / Portugal (Lisboa Edge)': '194.25.0.68',
        '🇦🇷 Argentina 1 (186.124.22.114)': '186.124.22.114',
        '🇦🇷 Argentina 2 (181.114.212.34)': '181.114.212.34',
        '🇦🇷 Argentina 3 (190.111.246.128)': '190.111.246.128',
        '🇺🇸 EE.UU. 2 (143.244.47.65)': '143.244.47.65',
        '🇺🇸 EE.UU. 3 (185.122.204.197)': '185.122.204.197',
        '🇧🇷 Brasil 2 (177.54.148.163)': '177.54.148.163',
        '🇧🇷 Brasil 3 (177.10.128.1)': '177.10.128.1',
        '🇰🇷 Corea 2 (121.125.60.151)': '121.125.60.151',
        '🇰🇷 Corea 3 (211.234.110.151)': '211.234.110.151',
        '🇯🇵 Japón 2 (138.199.21.239)': '138.199.21.239',
        '🇬🇧 Reino Unido (88.215.47.1)': '88.215.47.1',
        [t('IP Personalizada', 'Custom IP')]: 'custom'
    };

    const STORE_LOCALES = {
        [t('Automático / Sin Cambios', 'Automatic / No Changes')]: '',
        'Chile (es-cl / CLP)': 'es-cl',
        'Argentina (es-ar / ARS)': 'es-ar',
        'México (es-mx / MXN)': 'es-mx',
        'Estados Unidos (en-us / USD)': 'en-us',
        'Brasil (pt-br / BRL)': 'pt-br',
        'España (es-es / EUR)': 'es-es'
    };

    const SERVER_LIST = [
        'Automático', 'ChileCentral', 'BrazilSouth', 'MexicoCentral', 'EastUS', 'EastUS2', 'WestUS', 'WestUS2', 'WestUS3',
        'NorthCentralUs', 'SouthCentralUS', 'JapanEast', 'KoreaCentral', 'CentralIndia', 'SouthIndia',
        'AustraliaEast', 'AustraliaSouthEast', 'UKSouth', 'WestEurope', 'SwedenCentral'
    ];

    const SERVER_TO_COUNTRY = {
        'ChileCentral': { flag: '🇨🇱', acronym: 'CL' },
        'BrazilSouth': { flag: '🇧🇷', acronym: 'BR' },
        'MexicoCentral': { flag: '🇲🇽', acronym: 'MX' },
        'EastUS': { flag: '🇺🇸', acronym: 'US' },
        'WestUS': { flag: '🇺🇸', acronym: 'US' },
        'JapanEast': { flag: '🇯🇵', acronym: 'JP' },
        'KoreaCentral': { flag: '🇰🇷', acronym: 'KR' }
    };

    const CODEC_LIST = ['🌟 Auto-Detect Máximo Soportado', 'Automático', 'VVC (H.266)', 'AV2', 'AV1', 'H265 (HEVC)', 'VP9', 'H264 Alto', 'H264 Medio', 'H264 Bajo'];

    // ==========================================
    // 3.5 MOTOR DE RESOLUCIÓN 1440P / 4K / 8K Y BYPASS MEDIACAPABILITIES
    // ==========================================
    function getTargetDimensions() {
        if (config.force8K || config.targetResProfile === '8k') return { w: 7680, h: 4320, label: '8K (4320p)', code: '4320p', gbh: '35 GB/h' };
        if (config.force4K || config.targetResProfile === '4k') return { w: 3840, h: 2160, label: '4K (2160p)', code: '2160p', gbh: '20 GB/h' };
        if (config.ultraResolution || config.targetResProfile === '1440p') return { w: 2560, h: 1440, label: '1440p (QHD)', code: '1440p', gbh: '12 GB/h' };
        return { w: Math.max(1920, REAL_PHYSICAL_W), h: Math.max(1080, REAL_PHYSICAL_H), label: '1080p (FHD)', code: '1080p', gbh: '7 GB/h' };
    }

    if (config.ultraResolution || config.force4K || config.force8K) {
        const dim = getTargetDimensions();
        try {
            Object.defineProperty(window.screen, 'width', { get: () => dim.w, configurable: true });
            Object.defineProperty(window.screen, 'height', { get: () => dim.h, configurable: true });
            Object.defineProperty(window.screen, 'availWidth', { get: () => dim.w, configurable: true });
            Object.defineProperty(window.screen, 'availHeight', { get: () => dim.h, configurable: true });
            Object.defineProperty(window, 'devicePixelRatio', { get: () => (dim.w >= 3840 ? 2 : 1.5), configurable: true });
        } catch (e) {}

        try {
            localStorage.setItem('StreamResolution', dim.code);
            localStorage.setItem('XboxCloudGaming_StreamResolution', dim.code);
        } catch (e) {}
    }

    if (navigator.mediaCapabilities && navigator.mediaCapabilities.decodingInfo) {
        const origDecodingInfo = navigator.mediaCapabilities.decodingInfo.bind(navigator.mediaCapabilities);
        navigator.mediaCapabilities.decodingInfo = async function (configuration) {
            if (config.ultraResolution || config.force4K || config.force8K) {
                try {
                    const res = await origDecodingInfo(configuration);
                    return Object.assign({}, res, { supported: true, smooth: true, powerEfficient: true });
                } catch (e) {
                    return { supported: true, smooth: true, powerEfficient: true };
                }
            }
            return origDecodingInfo(configuration);
        };
    }

    class XFlyInputEngine {
        constructor() { this.peerConnection = null; this.isStreaming = false; }
        initStreamEngine(pcInstance) {
            this.peerConnection = pcInstance;
            const receivers = this.peerConnection.getReceivers();
            receivers.forEach(r => {
                if ((r.track?.kind === 'video' || r.track?.kind === 'audio') && 'playoutDelayHint' in r && config.lowLatencyMode) {
                    r.playoutDelayHint = 0;
                }
            });
            this.isStreaming = true;
        }
    }
    const xflyEngine = new XFlyInputEngine();

    if (config.disableNetworkCheck) {
        try { Object.defineProperty(navigator, 'connection', { get: () => undefined }); } catch (e) {}
    }
    if (config.muteOnBlur) {
        window.addEventListener('blur', () => { const v = document.querySelector('video, audio'); if (v) v.muted = true; });
        window.addEventListener('focus', () => { const v = document.querySelector('video, audio'); if (v) v.muted = false; });
    }
    if (config.smartTvMode) {
        try { Object.defineProperty(window.navigator, 'userAgent', { value: window.navigator.userAgent + ' Smart-TV' }); } catch (e) {}
    }

    function getHeaderButtonLabel() {
        const dim = getTargetDimensions();
        if (config.enableServerLock && config.forceServer !== 'Automático') {
            const info = SERVER_TO_COUNTRY[config.forceServer];
            if (info) return `⚙️ MJS ${info.flag} ${info.acronym} [${dim.code}]`;
        }
        return `⚙️ MJS 🌐 ${dim.code}`;
    }

    // ==========================================
    // 4. POSICIONAMIENTO SUPREMO DEL HUD EN EL STREAM (INMUNE A REESCALADO)
    // ==========================================
    function ensureHudOnTopOfStream() {
        let hud = document.getElementById('bxc-hud');
        if (!hud) {
            if (!document.body) return null;
            hud = document.createElement('div');
            hud.id = 'bxc-hud';
            hud.className = `hud-${config.statsPosition} hud-layout-${config.osdLayout}`;
            document.body.appendChild(hud);
        }

        const videoEl = document.querySelector('video.game-stream-video') || document.querySelector('#game-stream video') || document.querySelector('video');
        const streamRoot = document.fullscreenElement ||
                           (videoEl && (videoEl.closest('#game-stream') || videoEl.parentElement?.parentElement || videoEl.parentElement)) ||
                           document.getElementById('game-stream') ||
                           document.body;

        if (streamRoot && hud.parentElement !== streamRoot) {
            streamRoot.appendChild(hud);
        }

        return hud;
    }

    window.addEventListener('fullscreenchange', () => {
        ensureHudOnTopOfStream();
    });

    // ==========================================
    // 5. INTERFAZ GRÁFICA MAESTRA (GLASSMORPHISM WIN 11)
    // ==========================================
    function createUI() {
        if (document.getElementById('bxc-panel')) return;

        const style = document.createElement('style');
        style.innerHTML = `
            #bxc-overlay { display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.35); z-index: 2147483646; justify-content: center; align-items: center; font-family: 'Segoe UI', Tahoma, Geneva, sans-serif; pointer-events: auto;}
            
            #bxc-panel { 
                position: absolute; top: 5%; left: 50%; transform: translateX(-50%); 
                background: rgba(20, 20, 20, 0.55) !important; 
                backdrop-filter: blur(24px) saturate(180%) !important; 
                -webkit-backdrop-filter: blur(24px) saturate(180%); 
                border: 1px solid rgba(255, 255, 255, 0.18); 
                box-shadow: 0 12px 40px 0 rgba(0, 0, 0, 0.6), inset 0 1px 0 rgba(255, 255, 255, 0.2); 
                color: #fff; width: 90vw; max-width: 950px; height: 85vh; max-height: 850px; min-width: 320px; min-height: 400px; border-radius: 12px; display: flex; flex-direction: column; opacity: ${config.menuOpacity / 100}; transition: opacity 0.2s; resize: both; overflow: hidden; container-type: inline-size; 
            }
            #bxc-panel:hover { opacity: 1; }
            
            #bxc-drag-handle { background: rgba(255, 255, 255, 0.06) !important; border-bottom: 1px solid rgba(255,255,255,0.12); color: #ddd; text-align: center; font-size: 11px; padding: 7px; cursor: move; user-select: none; font-weight: bold; letter-spacing: 1px; display: flex; justify-content: center; align-items: center;}
            #bxc-drag-handle:hover { color: #fff; background: rgba(255, 255, 255, 0.12) !important; }

            .bxc-tabs { display: flex; background: rgba(0, 0, 0, 0.25) !important; border-radius: 0; flex-wrap: wrap; border-bottom: 1px solid rgba(255,255,255,0.1);}
            .bxc-tab { flex: 1; padding: 12px 5px; background: none; border: none; color: #cfcfcf; cursor: pointer; font-size: 13px; font-weight: bold; transition: 0.2s; text-align: center; border-bottom: 3px solid transparent;}
            .bxc-tab:hover { color: #fff; background: rgba(255,255,255,0.08); }
            .bxc-tab.active { color: #fff; background: rgba(255, 255, 255, 0.12) !important; border-bottom: 3px solid rgba(255,255,255,0.85); }
            
            .bxc-content { flex: 1; overflow-y: auto; padding: 20px; display: none; }
            .bxc-content.active { display: flex; flex-direction: column; gap: 12px; }
            .bxc-section-title { color: #fff; font-size: 15px; border-bottom: 1px solid rgba(255, 255, 255, 0.2); padding-bottom: 5px; margin-top: 10px; margin-bottom: 5px; font-weight: bold; text-transform: uppercase; letter-spacing: 1px; text-shadow: 0 2px 4px rgba(0,0,0,0.6); }
            
            .bxc-row { display: flex; justify-content: space-between; align-items: center; background: rgba(255, 255, 255, 0.05) !important; padding: 12px 15px; border-radius: 8px; border: 1px solid rgba(255,255,255,0.08); box-shadow: inset 0 1px 0 rgba(255,255,255,0.08); transition: 0.2s; flex-wrap: wrap; gap: 8px;}
            .bxc-row:hover, .bxc-row.nav-focused { background: rgba(255, 255, 255, 0.14) !important; border-color: rgba(255,255,255,0.65); }
            .bxc-row label.title { font-size: 13px; font-weight: 500; color: #eee; flex-grow: 1; min-width: 200px; display: flex; align-items: center; text-shadow: 0 1px 2px rgba(0,0,0,0.8);}
            
            select, input[type="text"], input[type="number"] { background: rgba(0, 0, 0, 0.45) !important; color: #fff; border: 1px solid rgba(255, 255, 255, 0.2) !important; padding: 8px; border-radius: 6px; outline: none; min-width: 200px; width: 100%; cursor: pointer; transition: 0.2s;}
            select:hover, input[type="text"]:hover, input[type="number"]:hover { background: rgba(0, 0, 0, 0.65) !important; border-color: rgba(255, 255, 255, 0.4) !important; }
            .bxc-search { background: rgba(0, 0, 0, 0.5) !important; font-style: italic; margin-bottom: 5px;}
            .bxc-search:focus { border-color: #fff !important; }
            
            .bxc-row input[type="number"] { width: 80px; text-align: center; min-width: unset;}
            .bxc-row input[type="range"] { accent-color: #fff; width: 200px; cursor: pointer; }
            
            .switch { position: relative; display: inline-block; width: 40px; height: 20px; margin-left: 15px; flex-shrink: 0;}
            .switch input { opacity: 0; width: 0; height: 0; }
            .slider { position: absolute; cursor: pointer; top: 0; left: 0; right: 0; bottom: 0; background-color: rgba(0,0,0,0.5); transition: .4s; border-radius: 20px; border: 1px solid rgba(255,255,255,0.25); }
            .slider:before { position: absolute; content: ""; height: 14px; width: 14px; left: 3px; bottom: 2px; background-color: white; transition: .4s; border-radius: 50%; box-shadow: 0 2px 4px rgba(0,0,0,0.5);}
            input:checked + .slider { background-color: rgba(255, 255, 255, 0.35); border-color: rgba(255,255,255,0.6);}
            input:checked + .slider:before { transform: translateX(18px); }

            .btxcl-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 8px; margin-top: 10px; width: 100%; }
            .btxcl-grid label { font-size: 12px; display: flex; align-items: center; gap: 6px; background: rgba(255,255,255,0.05); padding: 8px 10px; border-radius: 6px; border: 1px solid rgba(255,255,255,0.1); cursor: pointer; transition: 0.2s;}
            .btxcl-grid label:hover { background: rgba(255,255,255,0.12); border-color: rgba(255,255,255,0.3); }
            .btxcl-grid input[type="checkbox"] { accent-color: #fff; width: 16px; height: 16px; cursor: pointer; }
            
            .bxc-footer { display: flex; padding: 15px; background: rgba(0, 0, 0, 0.35) !important; gap: 10px; border-top: 1px solid rgba(255,255,255,0.12); flex-wrap: wrap; }
            .bxc-btn { flex: 1; padding: 12px; border: none; border-radius: 6px; cursor: pointer; font-weight: bold; font-size: 14px; color: white; transition: 0.2s; text-shadow: 0 1px 2px rgba(0,0,0,0.5); min-width: 140px;}
            .btn-save { background: rgba(255, 255, 255, 0.15); border: 1px solid rgba(255,255,255,0.35); box-shadow: inset 0 1px 0 rgba(255,255,255,0.2); } .btn-save:hover { background: rgba(255, 255, 255, 0.28); }
            .btn-close { background: rgba(0, 0, 0, 0.45); border: 1px solid rgba(255,255,255,0.2); } .btn-close:hover { background: rgba(255, 255, 255, 0.15); }
            .btn-ai { background: rgba(16, 124, 16, 0.45); border: 1px solid rgba(82, 225, 114, 0.5); } .btn-ai:hover { background: rgba(16, 124, 16, 0.7); }

            @container (max-width: 550px) {
                .bxc-row { flex-direction: column; align-items: flex-start; }
                .bxc-row select, .bxc-row input[type="text"] { width: 100%; margin-top: 5px; }
                .bxc-tab { font-size: 10px; padding: 10px 2px; }
                .bxc-row label.title { font-size: 11.5px; }
                .bxc-section-title { font-size: 13px; }
                .bxc-btn { font-size: 11px; }
            }

            /* HUD en Capa de Hardware Suprema (Inmune a Reescalado, Shaders y Fullscreen) */
            #bxc-hud { 
                position: fixed !important; 
                background: rgba(15, 15, 15, 0.45) !important; 
                backdrop-filter: blur(14px) saturate(160%) !important; 
                -webkit-backdrop-filter: blur(14px) saturate(160%) !important; 
                border: 1px solid rgba(255, 255, 255, 0.2) !important; 
                color: rgba(255,255,255,${Math.max(0.2, config.statsOpacity / 100)}) !important; 
                padding: 4px 10px !important; font-family: 'Consolas', monospace !important; font-size: 10.5px !important; 
                z-index: 2147483647 !important; border-radius: 8px !important; 
                pointer-events: none !important; display: none; text-shadow: 0 1px 2px rgba(0,0,0,0.85) !important; 
                white-space: nowrap !important; box-shadow: 0 4px 16px rgba(0,0,0,0.45), inset 0 1px 0 rgba(255,255,255,0.2) !important;
                opacity: ${config.statsOpacity / 100};
                isolation: isolate !important;
                will-change: transform, opacity !important;
            }
            .hud-layout-horizontal { display: flex !important; flex-direction: row; flex-wrap: nowrap; gap: 8px; align-items: center; }
            .hud-layout-vertical { display: flex !important; flex-direction: column; gap: 2px; }
            .hud-top-left { top: 10px !important; left: 10px !important; transform: translate3d(0, 0, 9999px) !important; } 
            .hud-top-right { top: 10px !important; right: 10px !important; transform: translate3d(0, 0, 9999px) !important; }
            .hud-bottom-left { bottom: 50px !important; left: 10px !important; transform: translate3d(0, 0, 9999px) !important; } 
            .hud-bottom-right { bottom: 50px !important; right: 10px !important; transform: translate3d(0, 0, 9999px) !important; }
            .hud-top-center { top: 10px !important; left: 50% !important; transform: translate3d(-50%, 0, 9999px) !important; }
            .hud-bottom-center { bottom: 50px !important; left: 50% !important; transform: translate3d(-50%, 0, 9999px) !important; }
            .osd-row { display: inline-flex; gap: 3px; align-items: center; }
            .osd-label { color: inherit; opacity: 0.75; font-weight: bold; font-size: 10px; }

            #bxc-floating-gear { 
                position: fixed; top: 30px; left: 10px; z-index: 2147483645; 
                background: rgba(25, 25, 25, 0.45) !important; 
                backdrop-filter: blur(12px) saturate(160%) !important; 
                -webkit-backdrop-filter: blur(12px) saturate(160%); 
                color: #fff; border: 1px solid rgba(255, 255, 255, 0.25) !important; 
                width: 42px; height: 42px; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 20px; cursor: pointer; box-shadow: 0 4px 14px rgba(0,0,0,0.5), inset 0 1px 0 rgba(255,255,255,0.2); user-select: none; touch-action: none; transition: transform 0.1s; 
            }
            #bxc-floating-gear:hover { transform: scale(1.1); background: rgba(255, 255, 255, 0.2) !important; }

            .mjs-header-btn { display: flex; align-items: center; justify-content: center; background: transparent; border: none; color: #fff; font-weight: bold; font-size: 14px; cursor: pointer; padding: 0 16px; opacity: ${config.menuOpacity / 100}; transition: 0.2s; }
            .mjs-header-btn:hover { background: rgba(255, 255, 255, 0.1); opacity: 1; }
        `;
        document.head.appendChild(style);

        const genOpts = (obj, sel) => Object.entries(obj).map(([k, v]) => `<option value="${v}" ${sel === v ? 'selected' : ''}>${k}</option>`).join('');
        const genArrOpts = (arr, sel) => arr.map(v => `<option value="${v}" ${sel === v ? 'selected' : ''}>${v}</option>`).join('');
        const toggle = (id, checked) => `<label class="switch"><input type="checkbox" id="${id}" ${checked ? 'checked' : ''}><span class="slider"></span></label>`;
        const hdrBadge = `<span style="font-size: 10px; padding: 3px 6px; border-radius: 4px; background: rgba(255, 255, 255, 0.15); color: #fff; border: 1px solid rgba(255,255,255,0.3); margin-left: auto;">${isHdrSupported ? '✅ Pantalla HDR' : '❌ Pantalla SDR (Simulador)'}</span>`;
        const screenBadge = `<span style="font-size: 10px; padding: 3px 6px; border-radius: 4px; background: rgba(255, 255, 255, 0.15); color: #fff; border: 1px solid rgba(255,255,255,0.3); margin-left: auto;">🖥️ Física: ${REAL_PHYSICAL_W}×${REAL_PHYSICAL_H} ${isHigherThanFHD ? '(>FHD Detectada)' : '(FHD Escalar)'}</span>`;

        const injectHeaderButton = () => {
            const headerRightSection = document.querySelector('div[class*="EdgewaterHeader-module__rightSectionSpacing"], div[class*="RemotePlayHeader-module__rightSectionSpacing"], div[class^="Header-module__rightSectionSpacing"]');
            if (headerRightSection && !document.getElementById('mjs-header-btn')) {
                const headerBtn = document.createElement('button');
                headerBtn.id = 'mjs-header-btn';
                headerBtn.className = 'mjs-header-btn';
                headerBtn.innerHTML = getHeaderButtonLabel();
                headerBtn.onclick = (e) => {
                    e.preventDefault();
                    const ov = document.getElementById('bxc-overlay');
                    if (ov) ov.style.display = 'flex';
                };
                headerRightSection.insertBefore(headerBtn, headerRightSection.firstChild);
            }
        };
        let headerThrottle = null;
        const headerObserver = new MutationObserver(() => {
            if (headerThrottle) return;
            headerThrottle = setTimeout(() => {
                headerThrottle = null;
                injectHeaderButton();
            }, 500);
        });
        if (document.body) {
            headerObserver.observe(document.body, { childList: true, subtree: true });
        }
        injectHeaderButton();

        const floatGear = document.createElement('div');
        floatGear.id = 'bxc-floating-gear';
        floatGear.innerHTML = '⚙️';
        floatGear.title = 'Arrastra para mover a cualquier costado o haz clic para abrir ajustes';
        document.body.appendChild(floatGear);

        let isDraggingGear = false, dragGearOffsetX = 0, dragGearOffsetY = 0, hasMovedGear = false;
        floatGear.addEventListener('pointerdown', (e) => {
            isDraggingGear = true; hasMovedGear = false;
            dragGearOffsetX = e.clientX - floatGear.getBoundingClientRect().left;
            dragGearOffsetY = e.clientY - floatGear.getBoundingClientRect().top;
            floatGear.setPointerCapture(e.pointerId);
        });
        floatGear.addEventListener('pointermove', (e) => {
            if (!isDraggingGear) return;
            hasMovedGear = true;
            floatGear.style.left = `${e.clientX - dragGearOffsetX}px`;
            floatGear.style.top = `${e.clientY - dragGearOffsetY}px`;
            floatGear.style.right = 'auto';
            floatGear.style.bottom = 'auto';
        });
        floatGear.addEventListener('pointerup', (e) => {
            if (!isDraggingGear) return;
            isDraggingGear = false;
            floatGear.releasePointerCapture(e.pointerId);
            let rect = floatGear.getBoundingClientRect();
            if (rect.left + rect.width / 2 < window.innerWidth / 2) {
                floatGear.style.left = '8px'; floatGear.style.right = 'auto';
            } else {
                floatGear.style.left = 'auto'; floatGear.style.right = '8px';
            }
        });
        floatGear.addEventListener('click', () => {
            if (!hasMovedGear) document.getElementById('bxc-overlay').style.display = 'flex';
        });

        const overlay = document.createElement('div');
        overlay.id = 'bxc-overlay';
        overlay.innerHTML = `
            <div id="bxc-panel">
                <div id="bxc-drag-handle">
                    <span>🤚 Arrastra desde aquí para mover el panel libremente</span>
                </div>
                <div class="bxc-tabs">
                    <button class="bxc-tab active" data-target="t-red">🚀 ${t('RED & CALIDAD', 'NET & QUALITY')}</button>
                    <button class="bxc-tab" data-target="t-in">🕹️ ${t('CONTROLES Y AUDIO', 'INPUT & AUDIO')}</button>
                    <button class="bxc-tab" data-target="t-vid">📺 ${t('VIDEO & AI SHADERS', 'VIDEO & AI SHADERS')}</button>
                    <button class="bxc-tab" data-target="t-hud">📊 ${t('HUD & SISTEMA', 'HUD & SYSTEM')}</button>
                </div>

                <!-- TABS 1: RED Y CALIDAD -->
                <div class="bxc-content active" id="t-red">
                    <div class="bxc-section-title">🚀 Ingeniería Inversa (HQ Streaming: Cloud & XRemote) ${q('Mejora resolución y bitrate tanto en Xbox Cloud Gaming como en Xbox Remote Play (XRemote).', 'Improves resolution and bitrate in Cloud Gaming & XRemote.')}</div>
                    <div class="bxc-row" style="flex-direction: column; align-items: flex-start;">
                        <div style="display:flex; width:100%; align-items:center; margin-bottom:5px;">
                            <label class="title">🎯 Perfil de Resolución Forzada (Selector Nativo + Stream) ${q('Fuerza el apartado Resolución de Cloud Gaming y XRemote a 1440p, 4K u 8K certificando soporte en MediaCapabilities.', 'Forces Cloud Gaming & XRemote resolution selector.')}</label>
                            ${screenBadge}
                        </div>
                        <select id="i-resprofile">
                            <option value="1440p" ${config.targetResProfile === '1440p' ? 'selected' : ''}>💎 Forzar 1440p QHD (2560×1440 - 12 GB/h) [Recomendado Cloud & XRemote]</option>
                            <option value="4k" ${config.targetResProfile === '4k' ? 'selected' : ''}>🖥️ Forzar 4K UHD (3840×2160 - 20 GB/h) [Pantallas > FHD / Supersampling]</option>
                            <option value="8k" ${config.targetResProfile === '8k' ? 'selected' : ''}>🌌 Forzar 8K Full UHD (7680×4320 - 35 GB/h) [Experimental Máximo]</option>
                            <option value="1080p" ${config.targetResProfile === '1080p' ? 'selected' : ''}>📺 Estándar 1080p FHD (1920×1080 - 7 GB/h)</option>
                        </select>
                    </div>
                    <div class="bxc-row"><label class="title">📡 Aplicar 1440p/4K/8K también en Xbox Remote Play (XRemote) ${q('Inyecta los overrides en /sessions/home/ para juego remoto desde consola.', 'Applies resolution overrides to Console Remote Play.')}</label>${toggle('i-xremote', config.forceXRemoteRes)}</div>
                    <div class="bxc-row"><label class="title">💎 Upscaling 1440p (HQ Assets) ${q('Texturas optimizadas en alta definición.', 'High-res textures.')}</label>${toggle('i-ultra', config.ultraResolution)}</div>
                    <div class="bxc-row"><label class="title">🖥️ Forzar 4K Nativo (UHD Flag) ${q('Máxima calidad nativa disponible.', 'Maximum native UHD quality.')}</label>${toggle('i-force4k', config.force4K)}</div>
                    <div class="bxc-row"><label class="title">🌌 Forzar 8K Nativo (Full UHD Flag) ${q('Activa banderas experimentales 8K (7680x4320) en el negociador de video.', 'Enables experimental 8K flags.')}</label>${toggle('i-force8k', config.force8K)}</div>
                    <div class="bxc-row"><label class="title">🛡️ Prevenir Caídas de Resolución ${q('Impide que el servidor baje la resolución ante picos de red.', 'Prevents dynamic resolution drops.')}</label>${toggle('i-nodrop', config.preventResDrops)}</div>
                    <div class="bxc-row"><label class="title">🚀 Bitrate Ilimitado (Zero Compresión) ${q('Ancho de banda máximo para nitidez perfecta.', 'Maximum bandwidth for clarity.')}</label>${toggle('i-infbit', config.infiniteBitrate)}</div>
                    <div class="bxc-row"><label class="title">✨ AI Reframing & Scaling (Insider) ${q('Escalado inteligente por IA.', 'AI intelligent scaling.')}</label>${toggle('i-ai', config.aiReframing)}</div>
                    <div class="bxc-row"><label class="title">🎞️ Desbloquear 120 FPS ${q('Soporte para altas tasas de refresco.', 'High refresh rate support.')}</label>${toggle('i-120fps', config.unlock120Fps)}</div>
                    <div class="bxc-row"><label class="title">🎮 Forzar Hardware "Series X" Exclusivo ${q('Asigna blades de máxima potencia.', 'Assigns top-tier server blades.')}</label>${toggle('i-seriesx', config.forceSeriesX)}</div>
                    <div class="bxc-row"><label class="title">🎧 Activar Audio 5.1 Surround ${q('Sonido espacial multicanal.', 'Multi-channel audio.')}</label>${toggle('i-51', config.enable51Audio)}</div>

                    <div class="bxc-section-title">🎥 Códecs Avanzados y HDR Universal</div>
                    <div class="bxc-row" style="flex-direction: column; align-items: flex-start;">
                        <label class="title" style="margin-bottom:5px;">🎞️ Forzar Códec de Video (VVC, AV2, AV1) ${q('Selección de códec avanzado.', 'Advanced codec selection.')}</label>
                        <select id="i-codec">${genArrOpts(CODEC_LIST, config.codecPref)}</select>
                    </div>
                    <div class="bxc-row" style="flex-direction: column; align-items: flex-start;">
                        <div style="display:flex; width:100%; align-items:center; margin-bottom:5px;">
                            <label class="title">🌈 Rango Dinámico Universal (HDR10, Dolby Vision, HLG) ${q('Activa simulación o HDR avanzado.', 'Activates HDR simulation.')}</label>
                            ${hdrBadge}
                        </div>
                        <select id="i-hdr">
                            <option value="off" ${config.hdrMode === 'off' ? 'selected' : ''}>SDR (Estándar)</option>
                            <option value="hdr10" ${config.hdrMode === 'hdr10' ? 'selected' : ''}>Forzar HDR10 / HLG (Avanzado)</option>
                            <option value="dolby" ${config.hdrMode === 'dolby' ? 'selected' : ''}>Forzar Dolby Vision (Extremo)</option>
                            <option value="hlg" ${config.hdrMode === 'hlg' ? 'selected' : ''}>Forzar HLG (Emisión)</option>
                        </select>
                        <div id="hdr-warning" style="display:none; color: #fff; font-size: 11.5px; margin-top: 8px; background: rgba(255, 255, 255, 0.1); padding: 8px; border-radius: 4px; border: 1px solid rgba(255, 255, 255, 0.3); width: 100%;">
                            ⚠️ <b>Advertencia:</b> Forzar modos HDR en pantallas no compatibles nativamente puede provocar colores alterados.
                        </div>
                    </div>

                    <div class="bxc-section-title">🌍 Enrutamiento Geográfico Global (Búsqueda Directa)</div>
                    <div class="bxc-row" style="flex-direction: column; align-items: flex-start;">
                        <div style="display:flex; width:100%; justify-content:space-between; align-items:center;">
                            <label class="title">🌐 Forzar Servidor Físico ${q('Conéctate al Data Center de tu preferencia.', 'Connect to your preferred DC.')}</label>
                            ${toggle('i-enablesrv', config.enableServerLock)}
                        </div>
                        <div id="div-srv-container" style="display:${config.enableServerLock ? 'block' : 'none'}; width: 100%; margin-top: 10px;">
                            <input type="text" id="search-srv" class="bxc-search" placeholder="🔍 Filtrar servidor (Ej: Chile, Brazil)..." autocomplete="off">
                            <select id="i-srv" size="4" style="margin-top: 5px;">${genArrOpts(SERVER_LIST, config.forceServer)}</select>
                        </div>
                    </div>
                    <div class="bxc-row" style="flex-direction: column; align-items: flex-start;">
                        <div style="display:flex; width:100%; justify-content:space-between; align-items:center;">
                            <label class="title">🛡️ X-Forwarded-For Bypass IP ${q('Proxy IP para regiones restringidas.', 'Proxy IP for restricted regions.')}</label>
                            ${toggle('i-net', config.enableNetworkBypass)}
                        </div>
                        <div id="div-ip-container" style="display:${config.enableNetworkBypass ? 'block' : 'none'}; width: 100%; margin-top: 10px;">
                            <input type="text" id="search-ip" class="bxc-search" placeholder="🔍 Filtrar IP (Ej: Argentina, USA)..." autocomplete="off">
                            <select id="i-ip" size="5" style="margin-top: 5px;">${genOpts(REGION_IPS, config.bypassIp)}</select>
                            <input type="text" id="i-customip" placeholder="Ej: 20.143.92.1" value="${config.customIp}" style="display:${config.bypassIp === 'custom' && config.enableNetworkBypass ? 'block' : 'none'}; width: 100%; margin-top: 10px;">
                        </div>
                    </div>
                    <div class="bxc-row">
                        <label class="title">🏪 Región de Tienda / Moneda ${q('Ajusta la divisa local de Microsoft Store.', 'Adjusts local store currency.')}</label>
                        <select id="i-store">${genOpts(STORE_LOCALES, config.storeLocale)}</select>
                    </div>
                    <div class="bxc-row"><label class="title">🔗 Preferir Nodos IPv6 ${q('Intenta usar redes IPv6 para evitar problemas de NAT.', 'Attempts to use IPv6 networks.')}</label>${toggle('i-ipv6', config.preferIPv6)}</div>
                </div>

                <!-- TABS 2: CONTROLES Y AUDIO -->
                <div class="bxc-content" id="t-in">
                    <div class="bxc-section-title">🎮 Rendimiento y Respuesta del Mando</div>
                    <div class="bxc-row"><label class="title">⚡ Polling Rate Mando (Hz/ms) ${q('Frecuencia de lectura del mando. 4ms (250Hz) es lo más rápido.', 'Controller reading frequency.')}</label>
                        <select id="i-poll" style="width: 150px;">
                            <option value="4" ${config.pollingRate == 4 ? 'selected' : ''}>250 Hz (4 ms)</option>
                            <option value="8" ${config.pollingRate == 8 ? 'selected' : ''}>125 Hz (8 ms)</option>
                            <option value="12" ${config.pollingRate == 12 ? 'selected' : ''}>83 Hz (12 ms)</option>
                            <option value="16" ${config.pollingRate == 16 ? 'selected' : ''}>62 Hz (16 ms)</option>
                        </select>
                    </div>
                    <div class="bxc-row">
                        <label class="title">🎯 Zonas Muertas L/R (%) ${q('Ignora los movimientos leves del Joystick (Anti-Drift).', 'Ignores slight Joystick movements.')}</label>
                        <div>L: <input type="number" id="i-dzl" min="0" max="100" value="${config.deadzoneL}"> R: <input type="number" id="i-dzr" min="0" max="100" value="${config.deadzoneR}"></div>
                    </div>
                    <div class="bxc-row"><label class="title">🔀 Modo Nintendo (Invertir A/B X/Y) ${q('Cambia los botones de lugar como en Nintendo Switch.', 'Swaps buttons like Nintendo Switch.')}</label>${toggle('i-swap', config.swapABXY)}</div>

                    <div class="bxc-section-title">📳 Motores de Vibración y Háptica (Fix Permanente)</div>
                    <div class="bxc-row"><label class="title">📳 Activar Vibración por Navegador / Nativa ${q('Intercepta GamepadHapticActuator y el VibratorManager de Android sin fugas de memoria.', 'Vibrates your controller via root prototype & native bridge.')}</label>${toggle('i-forcevib', config.forceVib)}</div>
                    <div class="bxc-row">
                        <label class="title">🎮 Perfil de Vibración ${q('DualSense 5 simula vibraciones hápticas ajustando las frecuencias cortas.', 'Changes the vibration behavior profile.')}</label>
                        <select id="i-vib-profile">
                            <option value="standard" ${config.vibProfile === 'standard' ? 'selected' : ''}>Xbox Estándar</option>
                            <option value="dualsense" ${config.vibProfile === 'dualsense' ? 'selected' : ''}>DualSense 5 (Háptica HD)</option>
                            <option value="pro" ${config.vibProfile === 'pro' ? 'selected' : ''}>Pro/Competitivo (Corta)</option>
                        </select>
                    </div>
                    <div class="bxc-row"><label class="title">📈 Intensidad de Vibración (<span id="val-vibint">${config.vibIntensity}</span>%)</label><input type="range" id="i-vib-int" min="10" max="100" value="${config.vibIntensity}"></div>

                    <div class="bxc-section-title">⌨️ Configuración de Teclado y Ratón</div>
                    <div class="bxc-row"><label class="title">⌨️ Soporte Nativo de Teclado Xbox</label>${toggle('i-keybnat', config.enableKeyboardNative)}</div>
                    <div class="bxc-row"><label class="title">🖱️ Soporte Nativo de Ratón Xbox</label>${toggle('i-mousenat', config.enableMouseNative)}</div>
                    <div class="bxc-row"><label class="title">⚔️ Forzar M&K en juegos de Consola (Emulación)</label>${toggle('i-keybforce', config.forceKeyboardConsole)}</div>
                    <div class="bxc-row"><label class="title">🕹️ Emular WASD a Joystick</label>${toggle('i-mnkemu', config.enableMnKEmulation)}</div>
                    <div class="bxc-row">
                        <label class="title">🛡️ Solo M&K Nativo (Evitar Baneos) ${q('Desactiva la emulación y asegura que tu cliente envíe datos de M&K solo cuando el juego lo soporte nativamente.', 'Disables M&K emulation for strict anti-cheat safety.')}</label>
                        ${toggle('i-safenat', config.safeNativeModeOnly)}
                    </div>

                    <div class="bxc-section-title">📱 Controles Táctiles y Multijugador</div>
                    <div class="bxc-row"><label class="title">📱 Activar Táctil Nativo (Solo juegos compatibles)</label>${toggle('i-touchnat', config.enableTouchNative)}</div>
                    <div class="bxc-row"><label class="title">🕹️ Forzar Joystick Virtual en pantalla (Cualquier juego)</label>${toggle('i-touchvirt', config.forceTouchVirtual)}</div>
                    <div class="bxc-row"><label class="title">👥 Habilitar Co-Op Local (Multimando)</label>${toggle('i-coop', config.enableCoOp)}</div>

                    <div class="bxc-section-title">🔊 Audio Avanzado</div>
                    <div class="bxc-row"><label class="title">🎤 Micrófono Passthrough (Chat de voz)</label>${toggle('i-mic', config.enableMic)}</div>
                    <div class="bxc-row"><label class="title">📢 Audio Volume Booster (+100%)</label>${toggle('i-vol', config.volBooster)}</div>
                    <div class="bxc-row"><label class="title">🔄 Combinar Pistas A/V (Fix Sync)</label>${toggle('i-comb', config.combineAV)}</div>
                    <div class="bxc-row"><label class="title">🔇 Silenciar si cambio de Pestaña</label>${toggle('i-mute', config.muteOnBlur)}</div>
                </div>

                <!-- TABS 3: VIDEO Y AI SHADERS -->
                <div class="bxc-content" id="t-vid">
                    <div class="bxc-section-title">🧠 Shaders de Escalado IA (Real-ESRGAN & SeedVR2)</div>
                    <div class="bxc-row" style="flex-direction: column; align-items: flex-start;">
                        <label class="title" style="margin-bottom:5px;">✨ Motor de Reconstrucción y Nitidez (GPU SVG Pipeline) ${q('Aplica pipelines matemáticos inspirados en Real-ESRGAN y SeedVR2 directo en el compositor GPU sin latencia ni pantalla negra.', 'Real-time GPU SVG shader pipelines.')}</label>
                        <select id="i-fsrmode">
                            <option value="esrgan_srvgg" ${config.fsrCasMode === 'esrgan_srvgg' ? 'selected' : ''}>🌟 Real-ESRGAN (SRVGG Compact - Limpieza + Nitidez Realista)</option>
                            <option value="esrgan_anime" ${config.fsrCasMode === 'esrgan_anime' ? 'selected' : ''}>🎌 Real-ESRGAN (AnimeVideo - Bordes Definidos / Cel-Shading)</option>
                            <option value="seedvr2" ${config.fsrCasMode === 'seedvr2' ? 'selected' : ''}>🎬 SeedVR2 (Detail Enhancer + Micro-Contraste)</option>
                            <option value="cas" ${config.fsrCasMode === 'cas' ? 'selected' : ''}>⚡ AMD FidelityFX CAS (Clásico)</option>
                            <option value="usm" ${config.fsrCasMode === 'usm' ? 'selected' : ''}>🔍 Unsharp Mask (USM Estándar)</option>
                            <option value="none" ${config.fsrCasMode === 'none' ? 'selected' : ''}>❌ Desactivado</option>
                        </select>
                    </div>
                    <div class="bxc-row">
                        <label class="title">🎨 Estabilizador de Color SeedVR2 (Color Fix) ${q('Inspirado en src/utils/color_fix.py de SeedVR2: corrige la pérdida de rango tonal y estabiliza la luminancia del stream.', 'Stabilizes stream luminance and chroma.')}</label>
                        ${toggle('i-seedcolor', config.seedvrColorFix)}
                    </div>
                    <div class="bxc-row"><label class="title">🔍 Intensidad del Motor IA / Nitidez (<span id="val-sha">${config.vidSharpness}</span>) ${q('Ajusta la fuerza de reconstrucción de bordes en tiempo real.', 'Adjusts edge reconstruction strength.')}</label><input type="range" id="i-sha" min="0" max="10" value="${config.vidSharpness}"></div>

                    <div class="bxc-section-title">📺 Pantalla y Relación de Aspecto</div>
                    <div class="bxc-row"><label class="title">🪟 Auto-Fullscreen & Landscape</label>${toggle('i-full', config.autoFullscreen)}</div>
                    <div class="bxc-row">
                        <label class="title">↔️ Estiramiento (Stretch)</label>
                        <select id="i-stretch">
                            <option value="normal" ${config.videoStretch === 'normal' ? 'selected' : ''}>Normal</option>
                            <option value="fill" ${config.videoStretch === 'fill' ? 'selected' : ''}>Rellenar Todo</option>
                            <option value="custom" ${config.videoStretch === 'custom' ? 'selected' : ''}>Personalizado</option>
                        </select>
                    </div>
                    <div class="bxc-row">
                        <label class="title">📏 Aspect Ratio Ultrawide</label>
                        <select id="i-ratio">
                            <option value="16:9" ${config.aspectRatio === '16:9' ? 'selected' : ''}>16:9</option>
                            <option value="16:10" ${config.aspectRatio === '16:10' ? 'selected' : ''}>16:10</option>
                            <option value="18:9" ${config.aspectRatio === '18:9' ? 'selected' : ''}>18:9</option>
                            <option value="21:9" ${config.aspectRatio === '21:9' ? 'selected' : ''}>21:9</option>
                        </select>
                    </div>
                    <div class="bxc-row" id="div-stretchxy" style="display:${config.videoStretch === 'custom' ? 'flex' : 'none'};">
                        <label class="title">📐 Escala X/Y Manual</label>
                        <div>X: <input type="number" id="i-sx" step="0.01" value="${config.stretchX}"> Y: <input type="number" id="i-sy" step="0.01" value="${config.stretchY}"></div>
                    </div>

                    <div class="bxc-section-title">🎨 Parámetros de Imagen Adicionales</div>
                    <div class="bxc-row"><label class="title">☀️ Brillo (<span id="val-bri">${config.vidBrightness}</span>%)</label><input type="range" id="i-bri" min="50" max="150" value="${config.vidBrightness}"></div>
                    <div class="bxc-row"><label class="title">🌗 Contraste (<span id="val-con">${config.vidContrast}</span>%)</label><input type="range" id="i-con" min="50" max="150" value="${config.vidContrast}"></div>
                    <div class="bxc-row"><label class="title">🌈 Saturación (<span id="val-sat">${config.vidSaturation}</span>%)</label><input type="range" id="i-sat" min="50" max="200" value="${config.vidSaturation}"></div>
                </div>

                <!-- TABS 4: HUD Y SISTEMA -->
                <div class="bxc-content" id="t-hud">
                    <div class="bxc-section-title">🔐 Solución de Inicio de Sesión (XAL Fix)</div>
                    <div class="bxc-row">
                        <label class="title">🔐 Iniciar Sesión Segura (Evitar Error 403) ${q('Si ves un error 403 al intentar loguearte, usa este botón para iniciar sesión mediante el motor nativo XAL con firmas ECDSA.', 'Fixes 403 login errors using native XAL engine.')}</label>
                        <button class="bxc-btn btn-save" id="btn-fix-login" style="flex:0 0 auto; padding: 8px 16px; font-size: 12px; background: #107C10 !important; color: white !important;">🔐 Login Seguro XAL</button>
                    </div>

                    <div class="bxc-section-title">🤖 Copiloto IA (Gemini 3.1 Flash / Argon)</div>
                    <div class="bxc-row">
                        <label class="title">🤖 Asistente de Streaming y Estrategia IA ${q('Abre el asistente nativo de IA para diagnóstico de latencia en tiempo real, recomendaciones de shaders o tácticas complejas.', 'Opens native Gemini AI Assistant for stream diagnostics and game strategy.')}</label>
                        <button class="bxc-btn btn-ai" id="btn-open-ai" style="flex:0 0 auto; padding: 8px 16px; font-size: 12px;">⚡ Abrir Copiloto IA</button>
                    </div>

                    <div class="bxc-section-title">🧹 Detox Post-Juego y Optimización de Memoria</div>
                    <div class="bxc-row">
                        <label class="title">🧹 Auto-Detox de Caché al Cerrar un Juego ${q('Limpia automáticamente imágenes, videos, fuentes y buffers pesados al salir de un juego, manteniendo intacta tu sesión de Xbox y tus configuraciones.', 'Automatically purges heavy media/font caches on game exit without touching sessions or settings.')}</label>
                        ${toggle('i-autodetox', config.autoDetoxCache)}
                    </div>
                    <div class="bxc-row">
                        <label class="title">✨ Ejecutar Detox Manual Ahora ${q('Libera inmediatamente la caché multimedia acumulada sin cerrar tu sesión ni perder ajustes.', 'Immediately frees cached media and buffers.')}</label>
                        <button class="bxc-btn btn-save" id="btn-detox-now" style="flex:0 0 auto; padding: 8px 16px; font-size: 12px;">🧹 Limpiar Caché Ahora</button>
                    </div>

                    <div class="bxc-section-title">⚙️ Sistema y QoL</div>
                    <div class="bxc-row"><label class="title">🛑 Desactivar Chequeo de Red (Anti-Lag)</label>${toggle('i-nocheck', config.disableNetworkCheck)}</div>
                    <div class="bxc-row"><label class="title">📺 Modo Smart TV (UI Grande)</label>${toggle('i-tv', config.smartTvMode)}</div>
                    <div class="bxc-row"><label class="title">🖱️ Ocultar Ratón Inactivo (2s)</label>${toggle('i-hms', config.hideIdleMouse)}</div>
                    <div class="bxc-row"><label class="title">👆 Swipe Táctil (Desliza para ocultar UI)</label>${toggle('i-swp', config.touchSlideHide)}</div>
                    <div class="bxc-row"><label class="title">🎮 Ocultar Táctil si hay Mando Físico</label>${toggle('i-htc', config.autoHideTouch)}</div>
                    <div class="bxc-row"><label class="title">🚀 Skip Splash (Omitir Logo Xbox)</label>${toggle('i-spl', config.skipSplash)}</div>
                    <div class="bxc-row"><label class="title">🚫 Bloquear Pop-up de Feedback Final</label>${toggle('i-fdb', config.skipFeedback)}</div>
                    <div class="bxc-row"><label class="title">🛡️ Bloquear Telemetría y Analíticas</label>${toggle('i-anal', config.blockAnalytics)}</div>
                    <div class="bxc-row"><label class="title">🤖 Anti-Kick ("¿Sigues ahí?")</label>${toggle('i-kick', config.antiKick)}</div>
                    <div class="bxc-row"><label class="title">⚡ Desactivar Animaciones CSS</label>${toggle('i-ani', config.disableUIAnim)}</div>
                    <div class="bxc-row"><label class="title">👻 Opacidad de este Menú (<span id="val-menuop">${config.menuOpacity}</span>%)</label><input type="range" id="i-menuop" min="10" max="100" value="${config.menuOpacity}"></div>

                    <div class="bxc-section-title">📊 OSD Ghost HUD (Prioridad #1 Sobre Reescalado)</div>
                    <div class="bxc-row"><label class="title">🟢 Activar HUD Dinámico (Capa Suprema en Stream)</label>${toggle('i-stats', config.showStats)}</div>
                    <div class="bxc-row">
                        <label class="title">📋 Diseño del HUD</label>
                        <select id="i-osdlayout">
                            <option value="horizontal" ${config.osdLayout === 'horizontal' ? 'selected' : ''}>Barra Horizontal (Fija en Línea)</option>
                            <option value="vertical" ${config.osdLayout === 'vertical' ? 'selected' : ''}>Lista Vertical</option>
                        </select>
                    </div>
                    <div class="bxc-row">
                        <label class="title">📌 Posición</label>
                        <select id="i-statspos">
                            <option value="top-left" ${config.statsPosition === 'top-left' ? 'selected' : ''}>Arriba Izq.</option>
                            <option value="top-center" ${config.statsPosition === 'top-center' ? 'selected' : ''}>Arriba Centro</option>
                            <option value="top-right" ${config.statsPosition === 'top-right' ? 'selected' : ''}>Arriba Der.</option>
                            <option value="bottom-left" ${config.statsPosition === 'bottom-left' ? 'selected' : ''}>Abajo Izq.</option>
                            <option value="bottom-center" ${config.statsPosition === 'bottom-center' ? 'selected' : ''}>Abajo Centro</option>
                            <option value="bottom-right" ${config.statsPosition === 'bottom-right' ? 'selected' : ''}>Abajo Der.</option>
                        </select>
                    </div>
                    <div class="bxc-row"><label class="title">👻 Opacidad HUD (<span id="val-statsop">${config.statsOpacity}</span>%)</label><input type="range" id="i-statsop" min="10" max="100" value="${config.statsOpacity}"></div>
                    
                    <div class="bxc-section-title">🔍 Métricas Expandidas OSD</div>
                    <div class="btxcl-grid">
                        <label><input type="checkbox" id="osd-time" ${config.osdTime ? 'checked' : ''}> ⏱️ Reloj</label>
                        <label><input type="checkbox" id="osd-play" ${config.osdPlay ? 'checked' : ''}> ⏳ T. Jugado</label>
                        <label><input type="checkbox" id="osd-batt" ${config.osdBatt ? 'checked' : ''}> 🔋 Batería</label>
                        <label><input type="checkbox" id="osd-ping" ${config.osdPing ? 'checked' : ''}> 📶 Ping</label>
                        <label><input type="checkbox" id="osd-jit" ${config.osdJit ? 'checked' : ''}> 〰️ Jitter</label>
                        <label><input type="checkbox" id="osd-res" ${config.osdRes ? 'checked' : ''}> 🖥️ Resol.</label>
                        <label><input type="checkbox" id="osd-fps" ${config.osdFps ? 'checked' : ''}> 🎞️ FPS</label>
                        <label><input type="checkbox" id="osd-btr" ${config.osdBtr ? 'checked' : ''}> 📈 Bitrate</label>
                        <label><input type="checkbox" id="osd-dt" ${config.osdDt ? 'checked' : ''}> ⚙️ Decode</label>
                        <label><input type="checkbox" id="osd-pl" ${config.osdPl ? 'checked' : ''}> 📉 Pack Loss</label>
                        <label><input type="checkbox" id="osd-fl" ${config.osdFl ? 'checked' : ''}> 🛑 Frm Drop</label>
                        <label><input type="checkbox" id="osd-dl" ${config.osdDl ? 'checked' : ''}> ⬇️ Data DL</label>
                        <label><input type="checkbox" id="osd-ul" ${config.osdUl ? 'checked' : ''}> ⬆️ Data UL</label>
                        <label><input type="checkbox" id="osd-net" ${config.osdNet ? 'checked' : ''}> 🌐 Tipo Red</label>
                    </div>
                </div>

                <div class="bxc-footer">
                    <button class="bxc-btn btn-ai" id="btn-copilot-footer">⚡ COPILOTO IA</button>
                    <button class="bxc-btn btn-ai" id="btn-opt-footer" style="background: #1565C0; border-color: #1E88E5;">🚀 OPTIMIZAR JUEGO</button>
                    <button class="bxc-btn btn-save" id="btn-save">💾 GUARDAR Y APLICAR</button>
                    <button class="bxc-btn btn-close" id="btn-close">❌ CERRAR</button>
                </div>
            </div>
        `;
        document.body.appendChild(overlay);

        document.getElementById('btn-detox-now').addEventListener('click', (e) => {
            e.preventDefault();
            runPostGameCacheDetox(true);
        });

        const openAiHandler = (e) => {
            e.preventDefault();
            GodModeNativeBridge.openAiCopilot();
        };
        const btnOpenAi = document.getElementById('btn-open-ai');
        if (btnOpenAi) btnOpenAi.addEventListener('click', openAiHandler);
        const btnCopilotFooter = document.getElementById('btn-copilot-footer');
        if (btnCopilotFooter) btnCopilotFooter.addEventListener('click', openAiHandler);

        const btnOptFooter = document.getElementById('btn-opt-footer');
        if (btnOptFooter) {
            btnOptFooter.addEventListener('click', (e) => {
                e.preventDefault();
                GodModeNativeBridge.openOptimizationSettings();
            });
        }

        const btnFixLogin = document.getElementById('btn-fix-login');
        if (btnFixLogin) {
            btnFixLogin.addEventListener('click', (e) => {
                e.preventDefault();
                GodModeNativeBridge.openXboxLogin();
            });
        }

        const selResProfile = document.getElementById('i-resprofile');
        const chkUltra = document.getElementById('i-ultra');
        const chk4K = document.getElementById('i-force4k');
        const chk8K = document.getElementById('i-force8k');

        selResProfile.addEventListener('change', (e) => {
            const val = e.target.value;
            if (val === '8k') { chkUltra.checked = true; chk4K.checked = true; chk8K.checked = true; }
            else if (val === '4k') { chkUltra.checked = true; chk4K.checked = true; chk8K.checked = false; }
            else if (val === '1440p') { chkUltra.checked = true; chk4K.checked = false; chk8K.checked = false; }
            else { chkUltra.checked = false; chk4K.checked = false; chk8K.checked = false; }
        });

        chk8K.addEventListener('change', (e) => {
            if (e.target.checked) { chk4K.checked = true; chkUltra.checked = true; selResProfile.value = '8k'; }
            else if (chk4K.checked) selResProfile.value = '4k';
            else if (chkUltra.checked) selResProfile.value = '1440p';
            else selResProfile.value = '1080p';
        });

        chk4K.addEventListener('change', (e) => {
            if (e.target.checked) { chkUltra.checked = true; if (!chk8K.checked) selResProfile.value = '4k'; }
            else { chk8K.checked = false; selResProfile.value = chkUltra.checked ? '1440p' : '1080p'; }
        });

        chkUltra.addEventListener('change', (e) => {
            if (e.target.checked) { if (!chk4K.checked && !chk8K.checked) selResProfile.value = '1440p'; }
            else { chk4K.checked = false; chk8K.checked = false; selResProfile.value = '1080p'; }
        });

        const checkSafeNat = document.getElementById('i-safenat');
        const checkForceKb = document.getElementById('i-keybforce');
        const checkEmuWASD = document.getElementById('i-mnkemu');
        checkSafeNat.addEventListener('change', (e) => {
            if (e.target.checked) {
                checkForceKb.checked = false; checkForceKb.disabled = true;
                checkEmuWASD.checked = false; checkEmuWASD.disabled = true;
            } else {
                checkForceKb.disabled = false;
                checkEmuWASD.disabled = false;
            }
        });
        if (config.safeNativeModeOnly) {
            checkForceKb.disabled = true;
            checkEmuWASD.disabled = true;
        }

        const selectHdr = document.getElementById('i-hdr');
        const warnHdr = document.getElementById('hdr-warning');
        selectHdr.addEventListener('change', (e) => {
            warnHdr.style.display = e.target.value !== 'off' ? 'block' : 'none';
        });
        if (config.hdrMode !== 'off') warnHdr.style.display = 'block';

        const panel = document.getElementById('bxc-panel');
        const dragHandle = document.getElementById('bxc-drag-handle');
        let isDraggingPanel = false, panelOffsetX = 0, panelOffsetY = 0;

        dragHandle.addEventListener('pointerdown', (e) => {
            if (e.target.tagName === 'BUTTON') return;
            isDraggingPanel = true;
            const rect = panel.getBoundingClientRect();
            panelOffsetX = e.clientX - rect.left;
            panelOffsetY = e.clientY - rect.top;
            dragHandle.setPointerCapture(e.pointerId);
        });
        dragHandle.addEventListener('pointermove', (e) => {
            if (!isDraggingPanel) return;
            panel.style.transform = 'none';
            panel.style.left = (e.clientX - panelOffsetX) + 'px';
            panel.style.top = (e.clientY - panelOffsetY) + 'px';
        });
        dragHandle.addEventListener('pointerup', (e) => {
            isDraggingPanel = false;
            dragHandle.releasePointerCapture(e.pointerId);
        });

        function filterSelect(inputId, selectId) {
            const input = document.getElementById(inputId);
            const select = document.getElementById(selectId);
            const allOptions = Array.from(select.options);
            input.addEventListener('input', function () {
                const filter = this.value.toLowerCase();
                select.innerHTML = '';
                let hasMatch = false;
                for (let opt of allOptions) {
                    if (opt.text.toLowerCase().includes(filter)) {
                        select.appendChild(opt);
                        hasMatch = true;
                    }
                }
                if (!hasMatch) {
                    const noRes = document.createElement('option');
                    noRes.text = 'No se encontraron coincidencias';
                    noRes.disabled = true;
                    select.appendChild(noRes);
                }
            });
        }
        filterSelect('search-ip', 'i-ip');
        filterSelect('search-srv', 'i-srv');

        document.querySelectorAll('.bxc-tab').forEach(t => {
            t.addEventListener('click', (e) => {
                document.querySelectorAll('.bxc-tab').forEach(b => b.classList.remove('active'));
                document.querySelectorAll('.bxc-content').forEach(c => c.classList.remove('active'));
                e.target.classList.add('active');
                document.getElementById(e.target.dataset.target).classList.add('active');
            });
        });

        document.getElementById('i-net').addEventListener('change', e => document.getElementById('div-ip-container').style.display = e.target.checked ? 'block' : 'none');
        document.getElementById('i-ip').addEventListener('change', e => document.getElementById('i-customip').style.display = e.target.value === 'custom' ? 'block' : 'none');
        document.getElementById('i-enablesrv').addEventListener('change', e => document.getElementById('div-srv-container').style.display = e.target.checked ? 'block' : 'none');
        document.getElementById('i-stretch').addEventListener('change', e => document.getElementById('div-stretchxy').style.display = e.target.value === 'custom' ? 'flex' : 'none');

        ['statsop', 'menuop', 'bri', 'con', 'sat', 'sha', 'vib-int'].forEach(id => {
            const el = document.getElementById(`i-${id}`);
            if (el) {
                el.addEventListener('input', (e) => {
                    let valEl = document.getElementById(`val-${id.replace('-', '')}`);
                    if (valEl) valEl.innerText = e.target.value;

                    if (id === 'menuop') {
                        document.getElementById('bxc-panel').style.opacity = e.target.value / 100;
                        const btn = document.getElementById('mjs-header-btn');
                        if (btn) btn.style.opacity = e.target.value / 100;
                    }
                    if (id === 'statsop') {
                        const hud = document.getElementById('bxc-hud');
                        if (hud) hud.style.opacity = e.target.value / 100;
                    }
                    if (['bri', 'con', 'sat', 'sha'].includes(id)) {
                        config.vidBrightness = parseInt(document.getElementById('i-bri').value);
                        config.vidContrast = parseInt(document.getElementById('i-con').value);
                        config.vidSaturation = parseInt(document.getElementById('i-sat').value);
                        config.vidSharpness = parseInt(document.getElementById('i-sha').value);
                        window.applyVideoFilters();
                    }
                });
            }
        });

        document.getElementById('i-fsrmode').addEventListener('change', (e) => {
            config.fsrCasMode = e.target.value;
            window.applyVideoFilters();
        });
        document.getElementById('i-seedcolor').addEventListener('change', (e) => {
            config.seedvrColorFix = e.target.checked;
            window.applyVideoFilters();
        });

        document.getElementById('btn-close').addEventListener('click', () => overlay.style.display = 'none');
        overlay.addEventListener('click', (e) => { if (e.target === overlay) overlay.style.display = 'none'; });

        document.getElementById('btn-save').addEventListener('click', () => {
            try {
                config.disableNetworkCheck = document.getElementById('i-nocheck').checked;
                config.enableNetworkBypass = document.getElementById('i-net').checked;
                config.bypassIp = document.getElementById('i-ip').value;
                config.customIp = document.getElementById('i-customip').value;
                config.storeLocale = document.getElementById('i-store').value;
                config.enableServerLock = document.getElementById('i-enablesrv').checked;
                config.forceServer = document.getElementById('i-srv').value;

                config.targetResProfile = document.getElementById('i-resprofile').value;
                config.forceXRemoteRes = document.getElementById('i-xremote').checked;
                config.ultraResolution = document.getElementById('i-ultra').checked;
                config.force4K = document.getElementById('i-force4k').checked;
                config.force8K = document.getElementById('i-force8k').checked;
                config.preventResDrops = document.getElementById('i-nodrop').checked;
                config.infiniteBitrate = document.getElementById('i-infbit').checked;
                config.aiReframing = document.getElementById('i-ai').checked;
                config.unlock120Fps = document.getElementById('i-120fps').checked;
                config.forceSeriesX = document.getElementById('i-seriesx').checked;
                config.preferIPv6 = document.getElementById('i-ipv6').checked;
                config.codecPref = document.getElementById('i-codec').value;
                config.enableCodecOverride = config.codecPref !== 'Automático';
                config.hdrMode = document.getElementById('i-hdr').value;

                config.safeNativeModeOnly = document.getElementById('i-safenat').checked;
                config.enableKeyboardNative = document.getElementById('i-keybnat').checked;
                config.forceKeyboardConsole = config.safeNativeModeOnly ? false : document.getElementById('i-keybforce').checked;
                config.enableMnKEmulation = config.safeNativeModeOnly ? false : document.getElementById('i-mnkemu').checked;
                config.enableMouseNative = document.getElementById('i-mousenat').checked;

                config.enableTouchNative = document.getElementById('i-touchnat').checked;
                config.forceTouchVirtual = document.getElementById('i-touchvirt').checked;
                config.enableCoOp = document.getElementById('i-coop').checked;
                config.swapABXY = document.getElementById('i-swap').checked;

                config.forceVib = document.getElementById('i-forcevib').checked;
                config.vibProfile = document.getElementById('i-vib-profile').value;
                config.vibIntensity = parseInt(document.getElementById('i-vib-int').value);

                config.pollingRate = parseInt(document.getElementById('i-poll').value);
                config.deadzoneL = parseInt(document.getElementById('i-dzl').value);
                config.deadzoneR = parseInt(document.getElementById('i-dzr').value);

                config.enableMic = document.getElementById('i-mic').checked;
                config.enable51Audio = document.getElementById('i-51').checked;
                config.volBooster = document.getElementById('i-vol').checked;
                config.combineAV = document.getElementById('i-comb').checked;
                config.muteOnBlur = document.getElementById('i-mute').checked;

                config.autoFullscreen = document.getElementById('i-full').checked;
                config.fsrCasMode = document.getElementById('i-fsrmode').value;
                config.seedvrColorFix = document.getElementById('i-seedcolor').checked;
                config.videoStretch = document.getElementById('i-stretch').value;
                config.aspectRatio = document.getElementById('i-ratio').value;
                config.stretchX = parseFloat(document.getElementById('i-sx').value);
                config.stretchY = parseFloat(document.getElementById('i-sy').value);
                config.vidBrightness = parseInt(document.getElementById('i-bri').value);
                config.vidContrast = parseInt(document.getElementById('i-con').value);
                config.vidSaturation = parseInt(document.getElementById('i-sat').value);
                config.vidSharpness = parseInt(document.getElementById('i-sha').value);

                config.autoDetoxCache = document.getElementById('i-autodetox').checked;
                config.smartTvMode = document.getElementById('i-tv').checked;
                config.hideIdleMouse = document.getElementById('i-hms').checked;
                config.touchSlideHide = document.getElementById('i-swp').checked;
                config.autoHideTouch = document.getElementById('i-htc').checked;
                config.skipSplash = document.getElementById('i-spl').checked;
                config.skipFeedback = document.getElementById('i-fdb').checked;
                config.blockAnalytics = document.getElementById('i-anal').checked;
                config.antiKick = document.getElementById('i-kick').checked;
                config.disableUIAnim = document.getElementById('i-ani').checked;
                config.menuOpacity = parseInt(document.getElementById('i-menuop').value);

                config.showStats = document.getElementById('i-stats').checked;
                config.osdLayout = document.getElementById('i-osdlayout').value;
                config.statsPosition = document.getElementById('i-statspos').value;
                config.statsOpacity = parseInt(document.getElementById('i-statsop').value);

                ['Fps', 'Ping', 'Btr', 'Res', 'Time', 'Play', 'Batt', 'Jit', 'Dt', 'Pl', 'Fl', 'Dl', 'Ul', 'Net'].forEach(m => {
                    const chk = document.getElementById(`osd-${m.toLowerCase()}`);
                    if (chk) config[`osd${m}`] = chk.checked;
                });

                saveConfig();
            } catch (err) {
                console.error('Error al guardar:', err);
            }

            overlay.style.display = 'none';
            window.location.reload();
        });

        ensureHudOnTopOfStream();
        injectUniversalTouchOverlay();
    }

    if (document.readyState === 'complete' || document.readyState === 'interactive') createUI();
    else window.addEventListener('DOMContentLoaded', createUI);

    // ==========================================
    // 5.5 INYECCIÓN EN EL SELECTOR NATIVO DE RESOLUCIÓN XBOX (StreamResolutionSelector)
    // ==========================================
    const SVG_RADIO_UNCHECKED = `<path d="M1024 0q141 0 272 36t245 103 207 160 160 208 103 245 37 272q0 141-36 272t-103 245-160 207-208 160-245 103-272 37q-141 0-272-36t-245-103-207-160-160-208-103-244-37-273q0-141 36-272t103-245 160-207 208-160T751 37t273-37zm0 1920q123 0 237-32t214-90 182-141 140-181 91-214 32-238q0-123-32-237t-90-214-141-182-181-140-214-91-238-32q-123 0-237 32t-214 90-182 141-140 181-91 214-32 238q0 123 32 237t90 214 141 182 181 140 214 91 238 32z"></path>`;
    const SVG_RADIO_CHECKED = `<path d="M1024 0q141 0 272 36t245 103 207 160 160 208 103 245 37 272q0 141-36 272t-103 245-160 207-208 160-245 103-272 37q-141 0-272-36t-245-103-207-160-160-208-103-244-37-273q0-141 36-272t103-245 160-207 208-160T751 37t273-37zm0 1920q123 0 237-32t214-90 182-141 140-181 91-214 32-238q0-123-32-237t-90-214-141-182-181-140-214-91-238-32q-123 0-237 32t-214 90-182 141-140 181-91 214-32 238q0 123 32 237t90 214 141 182 181 140 214 91 238 32zm0-1408q106 0 199 40t163 109 110 163 40 200q0 106-40 199t-109 163-163 110-200 40q-106 0-199-40t-163-109-110-163-40-200q0-106 40-199t109-163 163-110 200-40z"></path>`;

    function hijackNativeResolutionSelector() {
        const container = document.querySelector('div[class*="StreamResolutionSelector-module__optionsContainer"]');
        if (!container || container.dataset.mjsHijacked === 'true') return;

        const templateBtn = container.querySelector('button[role="radio"]');
        if (!templateBtn) return;

        container.dataset.mjsHijacked = 'true';
        const dim = getTargetDimensions();

        const desc = container.parentElement?.querySelector('p[class*="StreamResolutionSelector-module__description"]');
        if (desc) {
            desc.innerHTML = `⚡ <b>Motor MJS Activo (Cloud Gaming & XRemote):</b> Pantalla detectada (${REAL_PHYSICAL_W}×${REAL_PHYSICAL_H}) — Resolución forzada a <b>${dim.label}</b> con soporte de decodificación certificado.`;
        }

        const autoBtn = container.querySelector('button[aria-label*="Automático"], button[aria-label*="Automatic"]');
        if (autoBtn) {
            const priText = autoBtn.querySelector('span[class*="primaryText"]');
            const secText = autoBtn.querySelector('span[class*="secondaryText"]');
            if (priText) priText.textContent = `Automático (${dim.label} Forzado)`;
            if (secText) secText.textContent = `Configuración máxima desbloqueada para Cloud Gaming y XRemote (${dim.gbh})`;
        }

        const customResolutions = [
            { id: '8k', label: '8K (4320p Full UHD)', sub: `35 GB/h • Escalado Extremo (>FHD / 8K)`, profile: '8k' },
            { id: '4k', label: '4K (2160p UHD)', sub: `20 GB/h • Máxima Nitidez Nativa (Cloud & XRemote)`, profile: '4k' },
            { id: '1440p', label: '1440p (QHD Forzado)', sub: `12 GB/h • Alta Definición Forzada (Cloud & XRemote)`, profile: '1440p' }
        ];

        const updateVisualCheckState = (selectedProfile) => {
            container.querySelectorAll('button[role="radio"]').forEach(btn => {
                const isMatch = btn.dataset.mjsProfile === selectedProfile;
                btn.setAttribute('aria-checked', isMatch ? 'true' : 'false');
                const svg = btn.querySelector('svg');
                if (svg) svg.innerHTML = isMatch ? SVG_RADIO_CHECKED : SVG_RADIO_UNCHECKED;
            });
        };

        container.querySelectorAll('button[role="radio"]').forEach(btn => {
            const lbl = (btn.getAttribute('aria-label') || '').toLowerCase();
            if (lbl.includes('1080p') && !lbl.includes('autom')) {
                btn.dataset.mjsProfile = '1080p';
                btn.addEventListener('click', () => {
                    config.targetResProfile = '1080p';
                    config.ultraResolution = false; config.force4K = false; config.force8K = false;
                    saveConfig();
                    updateVisualCheckState('1080p');
                    Toast.show('Resolución ajustada a 1080p (FHD)', '📺');
                });
            }
        });

        customResolutions.forEach(item => {
            const clone = templateBtn.cloneNode(true);
            clone.setAttribute('aria-label', item.label);
            clone.dataset.mjsProfile = item.profile;
            const pri = clone.querySelector('span[class*="primaryText"]');
            const sec = clone.querySelector('span[class*="secondaryText"]');
            if (pri) pri.textContent = item.label;
            if (sec) sec.textContent = item.sub;

            clone.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                config.targetResProfile = item.profile;
                config.ultraResolution = true;
                config.force4K = (item.profile === '4k' || item.profile === '8k');
                config.force8K = (item.profile === '8k');
                localStorage.setItem('StreamResolution', item.profile === '8k' ? '4320p' : (item.profile === '4k' ? '2160p' : '1440p'));
                saveConfig();
                updateVisualCheckState(item.profile);
                if (autoBtn) {
                    const priText = autoBtn.querySelector('span[class*="primaryText"]');
                    if (priText) priText.textContent = `Automático (${item.label})`;
                }
                Toast.show(`Forzado activo: ${item.label} en Cloud & XRemote`, '💎');
            });

            if (autoBtn && autoBtn.nextSibling) {
                container.insertBefore(clone, autoBtn.nextSibling);
            } else {
                container.insertBefore(clone, container.firstChild);
            }
        });

        const activeProf = config.force8K ? '8k' : (config.force4K ? '4k' : (config.ultraResolution ? '1440p' : '1080p'));
        updateVisualCheckState(activeProf);
    }

    let resThrottle = null;
    const resObserver = new MutationObserver(() => {
        if (resThrottle) return;
        resThrottle = setTimeout(() => {
            resThrottle = null;
            hijackNativeResolutionSelector();
        }, 350);
    });
    if (document.documentElement) {
        resObserver.observe(document.documentElement, { childList: true, subtree: true });
    } else {
        window.addEventListener('DOMContentLoaded', () => {
            if (document.documentElement) {
                resObserver.observe(document.documentElement, { childList: true, subtree: true });
            }
        });
    }

    // ==========================================
    // 6. INTERCEPCIÓN FETCH (Bypass, Servidor, Cloud Gaming + XRemote Override)
    // ==========================================
    const NATIVE_FETCH = window.fetch;
    let bypassed = false;

    window.fetch = async (...args) => {
        let reqUrl = args[0] instanceof Request ? args[0].url : String(args[0] || '');

        if (config.blockAnalytics && (reqUrl.includes('qos.gameservices') || reqUrl.includes('browser.events.data'))) {
            return new Response(JSON.stringify({}), { status: 200 });
        }
        if (config.skipFeedback && reqUrl.includes('feedback')) {
            return new Response(JSON.stringify({}), { status: 200 });
        }

        if (config.enableNetworkBypass && config.bypassIp !== 'Desactivado' && config.bypassIp !== '' && !bypassed) {
            let activeIp = config.bypassIp === 'custom' ? config.customIp : config.bypassIp;
            if (activeIp && !reqUrl.includes('xhome') && reqUrl.endsWith('/v2/login/user')) {
                const addHeaders = (headers) => {
                    headers.set('X-Forwarded-For', activeIp);
                    headers.set('User-Agent', 'Xbox/2402.1001.20.0 (Windows NT 10.0; Win64; x64)');
                };
                if (args[0] instanceof Request) {
                    try { addHeaders(args[0].headers); }
                    catch (e) {
                        const newHeaders = new Headers(args[0].headers);
                        addHeaders(newHeaders);
                        args[0] = new Request(args[0], { headers: newHeaders, method: args[0].method, body: args[0].body, mode: args[0].mode, credentials: args[0].credentials });
                    }
                } else {
                    args[1] = args[1] || {};
                    args[1].headers = new Headers(args[1].headers || {});
                    addHeaders(args[1].headers);
                }
                bypassed = true;
            }
        }

        if (config.enableServerLock && config.forceServer !== 'Automático' && reqUrl.endsWith('/titles')) {
            const response = await NATIVE_FETCH(...args);
            try {
                const text = await response.clone().text();
                if (text.length > 0) {
                    let obj = JSON.parse(text);
                    if (obj.offeringSettings && obj.offeringSettings.regions) {
                        const selectedRegion = obj.offeringSettings.regions.find(r => r.name === config.forceServer);
                        if (selectedRegion) {
                            selectedRegion.isDefault = true;
                            obj.offeringSettings.regions = [selectedRegion];
                            return new Response(JSON.stringify(obj), { status: response.status, statusText: response.statusText, headers: response.headers });
                        }
                    }
                }
            } catch (e) {}
            return response;
        }

        const response = await NATIVE_FETCH(...args);

        const isCloudSession = reqUrl.includes('/sessions/cloud/');
        const isHomeSession = config.forceXRemoteRes && reqUrl.includes('/sessions/home/');

        if (reqUrl.endsWith('/configuration') && (isCloudSession || isHomeSession)) {
            try {
                const text = await response.clone().text();
                if (text.length > 0) {
                    let obj = JSON.parse(text);
                    let overrides = {};
                    try {
                        overrides = JSON.parse(obj.clientStreamingConfigOverrides || '{}');
                    } catch (e) {
                        overrides = {};
                    }

                    overrides.videoConfiguration = overrides.videoConfiguration || {};
                    overrides.features = overrides.features || {};
                    overrides.audioConfiguration = overrides.audioConfiguration || {};
                    overrides.inputConfiguration = overrides.inputConfiguration || {};

                    if (config.ultraResolution || config.force4K || config.force8K) {
                        const dim = getTargetDimensions();
                        overrides.videoConfiguration.width = dim.w;
                        overrides.videoConfiguration.height = dim.h;
                        overrides.videoConfiguration.maxWidth = dim.w;
                        overrides.videoConfiguration.maxHeight = dim.h;
                        overrides.videoConfiguration.maxBitrate = config.infiniteBitrate ? 2147483647 : (dim.w >= 3840 ? 80000000 : 50000000);
                        overrides.features.Enable1440p = true;
                        overrides.features.EnableQHD = true;
                        overrides.features.EnableResolutionSelector = true;
                        overrides.features.MaxResolution = dim.code;
                        if (config.force4K || config.force8K) {
                            overrides.features.Enable4K = true;
                            overrides.features.EnableUHD = true;
                        }
                        if (config.force8K) {
                            overrides.features.Enable8K = true;
                        }
                    }
                    if (config.preventResDrops) overrides.features.EnableResolutionDrops = false;

                    if (config.aiReframing) {
                        overrides.features.EnableStreamGate = false;
                        overrides.features.EnableHighResolutionHQ = true;
                        overrides.features.EnableChroma444 = true;
                        overrides.features.EnableAIScaling = true;
                        overrides.features.EnableAiframeGeneration = true;
                    }

                    if (config.unlock120Fps) {
                        overrides.videoConfiguration.enable120Fps = true;
                        overrides.features.Enable120Fps = true;
                    }
                    if (config.forceSeriesX && isCloudSession) overrides.features.LockToServerBlade = 'Anaconda';
                    if (config.lowLatencyMode) overrides.videoConfiguration.enableLowLatency = true;

                    if (config.enableCodecOverride) {
                        let pref = config.codecPref;
                        if (pref.includes('Auto-Detect Máximo')) {
                            if (browserHasAV1) { overrides.supportedCodecs = ['av1']; overrides.features.EnableAV1 = true; }
                            else if (browserHasHEVC) { overrides.supportedCodecs = ['hevc']; overrides.features.EnableHEVC = true; }
                            else if (browserHasVP9) { overrides.supportedCodecs = ['vp9']; }
                            else { overrides.supportedCodecs = ['h264']; }
                        } else if (pref.includes('VVC')) {
                            overrides.supportedCodecs = ['vvc', 'h266'];
                            overrides.features.EnableVVC = true;
                        } else if (pref.includes('AV2')) {
                            overrides.supportedCodecs = ['av2'];
                            overrides.features.EnableAV2 = true;
                        } else {
                            let c = pref.toLowerCase();
                            overrides.supportedCodecs = [c.includes('h264') ? 'h264' : (c === 'h265' ? 'hevc' : c)];
                            overrides.features.EnableAV1 = (c === 'av1');
                            overrides.features.EnableHEVC = (c === 'h265');
                        }
                    }

                    if (config.hdrMode === 'hdr10') overrides.videoConfiguration.enableHdr = true;
                    if (config.hdrMode === 'dolby') overrides.videoConfiguration.enableDolbyVision = true;
                    if (config.hdrMode === 'hlg') { overrides.videoConfiguration.enableHdr = true; overrides.videoConfiguration.enableHlg = true; }

                    if (config.enableMic) overrides.audioConfiguration.enableMicrophone = true;
                    if (config.enable51Audio) { overrides.audioConfiguration.enableSurroundSound = true; overrides.audioConfiguration.channels = 6; }

                    let enableKb = config.enableKeyboardNative;
                    let enableMs = config.enableMouseNative;

                    if (!config.safeNativeModeOnly && config.forceKeyboardConsole) {
                        enableKb = true;
                        enableMs = true;
                    }

                    if (enableKb || enableMs) {
                        overrides.inputConfiguration.enableKeyboardInput = enableKb;
                        overrides.inputConfiguration.enableMouseInput = enableMs;
                        overrides.inputConfiguration.enableAbsoluteMouse = false;
                    }

                    if (config.enableCoOp) overrides.inputConfiguration.useUnreliableInput = false;

                    if (config.forceTouchVirtual) {
                        overrides.inputConfiguration.enableTouchInput = true;
                        overrides.inputConfiguration.maxTouchPoints = 10;
                    } else if (config.enableTouchNative) {
                        overrides.inputConfiguration.enableTouchInput = true;
                    }

                    if (config.forceVib) overrides.inputConfiguration.enableVibration = true;

                    obj.clientStreamingConfigOverrides = JSON.stringify(overrides);
                    return new Response(JSON.stringify(obj), { status: response.status, statusText: response.statusText, headers: response.headers });
                }
            } catch (e) {}
        }
        return response;
    };

    // ==========================================
    // 7. WEBRTC, SDP IPV6/LOW-LATENCY, PRIMERA ACTIVACIÓN DE ESTADÍSTICAS, DETOX POST-JUEGO Y PIPELINE SVG IA
    // ==========================================
    let peerConnection = null;
    let sessionStartTime = 0;
    let statsIntervalId = null;
    const nativeAddIceCandidate = RTCPeerConnection.prototype.addIceCandidate;
    const nativeSetLocalDescription = RTCPeerConnection.prototype.setLocalDescription;

    // Priorización de candidatos IPv6 y optimización de ancho de banda en el SDP
    RTCPeerConnection.prototype.setLocalDescription = function (desc) {
        if (desc && desc.sdp) {
            let sdp = desc.sdp;
            if (config.preferIPv6) {
                sdp = sdp.replace(/^a=candidate:(\S+) (\d+) (\S+) (\d+) ([0-9a-fA-F:]+:[0-9a-fA-F:]+) /gm, (match, f, c, proto, prio, ip) => {
                    const boostedPrio = Math.min(2147483647, parseInt(prio, 10) + 500000);
                    return `a=candidate:${f} ${c} ${proto} ${boostedPrio} ${ip} `;
                });
            }
            desc.sdp = sdp;
        }
        return nativeSetLocalDescription.apply(this, arguments);
    };

    RTCPeerConnection.prototype.addIceCandidate = function (...args) {
        if (peerConnection !== this) {
            sessionStartTime = Date.now();
            peerConnection = this;
            wasInGameSession = true;

            // ACTIVAR LAS ESTADÍSTICAS (OSD) COMO PRIMERA FUNCIÓN AL INICIAR EL STREAM
            initStats();
            ensureHudOnTopOfStream();

            xflyEngine.initStreamEngine(this);

            // Sincronización de pistas A/V (combineAV) cuando se reciben los tracks remotos
            if (config.combineAV) {
                this.addEventListener('track', () => {
                    try {
                        const receivers = this.getReceivers();
                        const vTrack = receivers.find(r => r.track && r.track.kind === 'video')?.track;
                        const aTrack = receivers.find(r => r.track && r.track.kind === 'audio')?.track;
                        const vEl = document.querySelector('video.game-stream-video') || document.querySelector('#game-stream video');
                        if (vTrack && aTrack && vEl && vEl.srcObject instanceof MediaStream) {
                            if (!vEl.srcObject.getAudioTracks().length) {
                                vEl.srcObject.addTrack(aTrack);
                            }
                        }
                    } catch (e) {}
                });
            }

            // DETECTAR CIERRE DE LA SESIÓN WEBRTC PARA EJECUTAR EL DETOX AUTOMÁTICO
            this.addEventListener('connectionstatechange', () => {
                if (this.connectionState === 'connected') {
                    wasInGameSession = true;
                    ensureHudOnTopOfStream();
                    if (!config.safeNativeModeOnly && (config.enableMouseNative || config.forceKeyboardConsole || config.enableMnKEmulation)) {
                        GodModeNativeBridge.setPointerCapture(true);
                    }
                } else if (wasInGameSession && (this.connectionState === 'closed' || this.connectionState === 'disconnected' || this.connectionState === 'failed')) {
                    wasInGameSession = false;
                    GodModeNativeBridge.setPointerCapture(false);
                    const hud = document.getElementById('bxc-hud');
                    if (hud) hud.style.display = 'none';
                    runPostGameCacheDetox(false);
                }
            });
        }
        return nativeAddIceCandidate.apply(this, args);
    };

    // Parche en la raíz del prototipo de vibración + Enrutamiento al Puente Nativo de Android
    if (window.GamepadHapticActuator && GamepadHapticActuator.prototype.playEffect && !GamepadHapticActuator.prototype._bxWrapped) {
        const originalPlayEffect = GamepadHapticActuator.prototype.playEffect;
        GamepadHapticActuator.prototype.playEffect = function (type, params) {
            if ((type === 'dual-rumble' || type === 'trigger-rumble') && config.forceVib && params) {
                let intensity = (config.vibIntensity || 100) / 100;
                let adjustedParams = Object.assign({}, params);
                if (config.vibProfile === 'dualsense') {
                    adjustedParams.weakMagnitude = Math.min(1.0, (params.weakMagnitude || 0) * 1.45 * intensity);
                    adjustedParams.strongMagnitude = Math.min(1.0, (params.strongMagnitude || 0) * 0.85 * intensity);
                } else if (config.vibProfile === 'pro') {
                    adjustedParams.duration = Math.min(params.duration || 100, 140);
                    adjustedParams.weakMagnitude = Math.min(1.0, (params.weakMagnitude || 0) * intensity);
                    adjustedParams.strongMagnitude = Math.min(1.0, (params.strongMagnitude || 0) * intensity);
                } else {
                    adjustedParams.weakMagnitude = Math.min(1.0, (params.weakMagnitude || 0) * intensity);
                    adjustedParams.strongMagnitude = Math.min(1.0, (params.strongMagnitude || 0) * intensity);
                }
                GodModeNativeBridge.vibrate(adjustedParams.duration || 100, adjustedParams.weakMagnitude, adjustedParams.strongMagnitude);
                return originalPlayEffect.call(this, type, adjustedParams);
            }
            return originalPlayEffect.call(this, type, params);
        };
        GamepadHapticActuator.prototype._bxWrapped = true;
    }

    // Interceptar navigator.vibrate para soporte universal de vibración táctil y WebRTC
    try {
        if (typeof navigator !== 'undefined' && !navigator._bxVibWrapped) {
            const origNavVibrate = navigator.vibrate ? navigator.vibrate.bind(navigator) : null;
            navigator.vibrate = function(pattern) {
                try {
                    if (typeof pattern === 'number' && pattern > 0) {
                        GodModeNativeBridge.vibrate(pattern, 1.0, 1.0);
                    } else if (Array.isArray(pattern) && pattern.length > 0) {
                        GodModeNativeBridge.vibrate(pattern[0], 1.0, 1.0);
                    }
                } catch(e) {}
                if (origNavVibrate) {
                    try { return origNavVibrate(pattern); } catch(e) {}
                }
                return true;
            };
            navigator._bxVibWrapped = true;
        }
    } catch(e) {}

    // Inyectar vibrationActuator en navigator.getGamepads si el WebView no lo provee nativamente
    try {
        if (typeof navigator !== 'undefined' && navigator.getGamepads && !navigator._bxGpWrapped) {
            const origGetGamepads = navigator.getGamepads.bind(navigator);
            navigator.getGamepads = function() {
                const gps = origGetGamepads();
                if (gps && config.forceVib) {
                    for (let i = 0; i < gps.length; i++) {
                        const gp = gps[i];
                        if (gp && !gp.vibrationActuator) {
                            try {
                                gp.vibrationActuator = {
                                    type: 'dual-rumble',
                                    playEffect: function(type, params) {
                                        if (params) {
                                            let intensity = (config.vibIntensity || 100) / 100;
                                            let d = params.duration || 100;
                                            let w = (params.weakMagnitude || 0) * intensity;
                                            let s = (params.strongMagnitude || 0) * intensity;
                                            GodModeNativeBridge.vibrate(d, w, s);
                                        }
                                        return Promise.resolve('complete');
                                    },
                                    reset: function() { return Promise.resolve('complete'); }
                                };
                            } catch(e) {}
                        }
                    }
                }
                return gps;
            };
            navigator._bxGpWrapped = true;
        }
    } catch(e) {}

    const nativePlay = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function () {
        if (this.className && this.className.includes('XboxSplashVideo') && config.skipSplash) {
            this.volume = 0; this.style.display = 'none'; this.dispatchEvent(new Event('ended'));
            return Promise.resolve();
        }

        if (!this.src && this.parentElement && this.parentElement.dataset.testid === 'media-container') {
            initStats();
            ensureHudOnTopOfStream();
            wasInGameSession = true;

            if (config.volBooster && !this._bxAudioHooked) {
                try {
                    const audioCtx = new (window.AudioContext || window.webkitAudioContext)();
                    const source = audioCtx.createMediaElementSource(this);
                    const gainNode = audioCtx.createGain();
                    gainNode.gain.value = 2.0;
                    source.connect(gainNode);
                    gainNode.connect(audioCtx.destination);
                    this._bxAudioHooked = true;
                } catch (e) {}
            }

            this.addEventListener('playing', function showFunc() {
                this.removeEventListener('playing', showFunc);
                initStats();
                ensureHudOnTopOfStream();
                initSystemObservers();
                initSlideHide();
                if (config.autoFullscreen) setMachineFullScreen();
                window.applyVideoFilters();
                if (!window.__videoFilterInterval) {
                    window.__videoFilterInterval = setInterval(window.applyVideoFilters, 2000);
                }
            }, { once: true });
        }
        return nativePlay.apply(this, arguments);
    };

    // Motor de Shaders SVG multi-etapa inspirado en Real-ESRGAN (SRVGG/AnimeVideo) y SeedVR2 (Color Fix)
    window.applyVideoFilters = function () {
        ensureHudOnTopOfStream();

        let svgContainer = document.getElementById('bxc-svg-filters-container');
        if (!svgContainer) {
            svgContainer = document.createElement('div');
            svgContainer.id = 'bxc-svg-filters-container';
            svgContainer.style.display = 'none';
            svgContainer.innerHTML = `
                <svg xmlns="http://www.w3.org/2000/svg">
                    <defs>
                        <!-- 1. Clásico USM / AMD CAS -->
                        <filter id="bxc-filter-cas" color-interpolation-filters="sRGB">
                            <feConvolveMatrix id="bxc-matrix-cas" order="3" preserveAlpha="true" kernelMatrix="0 -1 0 -1 5 -1 0 -1 0" />
                        </filter>

                        <!-- 2. Real-ESRGAN (SRVGG Compact): Micro-Denoise + Reconstrucción de Bordes Simétrica -->
                        <filter id="bxc-filter-esrgan-srvgg" color-interpolation-filters="sRGB">
                            <feGaussianBlur in="SourceGraphic" stdDeviation="0.35" result="denoise" />
                            <feComposite in="SourceGraphic" in2="denoise" operator="arithmetic" k1="0" k2="0.75" k3="0.25" result="cleanBase" />
                            <feConvolveMatrix id="bxc-matrix-srvgg" in="cleanBase" order="3" preserveAlpha="true" kernelMatrix="-0.15 -0.35 -0.15 -0.35 3.0 -0.35 -0.15 -0.35 -0.15" />
                        </filter>

                        <!-- 3. Real-ESRGAN (AnimeVideo): Delineado Fuerte + Limpieza Cel-Shading -->
                        <filter id="bxc-filter-esrgan-anime" color-interpolation-filters="sRGB">
                            <feGaussianBlur in="SourceGraphic" stdDeviation="0.45" result="flatSmooth" />
                            <feComposite in="SourceGraphic" in2="flatSmooth" operator="arithmetic" k1="0" k2="0.65" k3="0.35" result="celBase" />
                            <feConvolveMatrix id="bxc-matrix-anime" in="celBase" order="3" preserveAlpha="true" kernelMatrix="-0.25 -0.5 -0.25 -0.5 4.0 -0.5 -0.25 -0.5 -0.25" result="sharpCel" />
                            <feComponentTransfer in="sharpCel">
                                <feFuncR type="linear" slope="1.04" intercept="-0.02"/>
                                <feFuncG type="linear" slope="1.04" intercept="-0.02"/>
                                <feFuncB type="linear" slope="1.04" intercept="-0.02"/>
                            </feComponentTransfer>
                        </filter>

                        <!-- 4. SeedVR2 Detail Enhancer: Nitidez Fina Diagonal -->
                        <filter id="bxc-filter-seedvr2" color-interpolation-filters="sRGB">
                            <feConvolveMatrix id="bxc-matrix-seedvr2" order="3" preserveAlpha="true" kernelMatrix="-0.1 -0.2 -0.1 -0.2 2.2 -0.2 -0.1 -0.2 -0.1" />
                        </filter>

                        <!-- 5. SeedVR2 Color Fix (Estabilización de Rango Luminancia/Croma) -->
                        <filter id="bxc-filter-seedvr-colorfix" color-interpolation-filters="sRGB">
                            <feColorMatrix type="matrix" values="
                                1.06 -0.03 -0.03 0 0.005
                                -0.02 1.05 -0.03 0 0.005
                                -0.03 -0.02 1.05 0 0.005
                                0     0     0    1 0" result="chromaFixed"/>
                            <feComponentTransfer in="chromaFixed">
                                <feFuncR type="gamma" amplitude="1" exponent="0.97" offset="0"/>
                                <feFuncG type="gamma" amplitude="1" exponent="0.97" offset="0"/>
                                <feFuncB type="gamma" amplitude="1" exponent="0.97" offset="0"/>
                            </feComponentTransfer>
                        </filter>
                    </defs>
                </svg>
            `;
            document.body.appendChild(svgContainer);
        }

        const video = document.querySelector('video.game-stream-video') || document.querySelector('#game-stream video') || document.querySelector('video');
        if (!(video instanceof HTMLVideoElement)) return;
        if (video.readyState < 2 || video.paused || video.ended) return;

        let filters = [];
        const s = config.vidSharpness;

        if (s > 0 && config.fsrCasMode !== 'none') {
            if (config.fsrCasMode === 'esrgan_srvgg') {
                const diag = (-0.04 * (s / 4)).toFixed(3);
                const adj = (-0.12 * (s / 4)).toFixed(3);
                const cent = (1 + (-4 * parseFloat(diag)) + (-4 * parseFloat(adj))).toFixed(3);
                const mat = document.getElementById('bxc-matrix-srvgg');
                if (mat) mat.setAttribute('kernelMatrix', `${diag} ${adj} ${diag} ${adj} ${cent} ${adj} ${diag} ${adj} ${diag}`);
                filters.push('url(#bxc-filter-esrgan-srvgg)');
            } else if (config.fsrCasMode === 'esrgan_anime') {
                const diag = (-0.06 * (s / 4)).toFixed(3);
                const adj = (-0.18 * (s / 4)).toFixed(3);
                const cent = (1 + (-4 * parseFloat(diag)) + (-4 * parseFloat(adj))).toFixed(3);
                const mat = document.getElementById('bxc-matrix-anime');
                if (mat) mat.setAttribute('kernelMatrix', `${diag} ${adj} ${diag} ${adj} ${cent} ${adj} ${diag} ${adj} ${diag}`);
                filters.push('url(#bxc-filter-esrgan-anime)');
            } else if (config.fsrCasMode === 'seedvr2') {
                const diag = (-0.03 * (s / 4)).toFixed(3);
                const adj = (-0.09 * (s / 4)).toFixed(3);
                const cent = (1 + (-4 * parseFloat(diag)) + (-4 * parseFloat(adj))).toFixed(3);
                const mat = document.getElementById('bxc-matrix-seedvr2');
                if (mat) mat.setAttribute('kernelMatrix', `${diag} ${adj} ${diag} ${adj} ${cent} ${adj} ${diag} ${adj} ${diag}`);
                filters.push('url(#bxc-filter-seedvr2)');
            } else if (config.fsrCasMode === 'cas') {
                let center = (7 - (s / 2 - 1) * 0.5).toFixed(1);
                const mat = document.getElementById('bxc-matrix-cas');
                if (mat) mat.setAttribute('kernelMatrix', `-0.125 -0.125 -0.125 -0.125 ${parseFloat(center) * 0.8} -0.125 -0.125 -0.125 -0.125`);
                filters.push('url(#bxc-filter-cas)');
            } else {
                let center = (7 - (s / 2 - 1) * 0.5).toFixed(1);
                const mat = document.getElementById('bxc-matrix-cas');
                if (mat) mat.setAttribute('kernelMatrix', `0 -1 0 -1 ${center} -1 0 -1 0`);
                filters.push('url(#bxc-filter-cas)');
            }
        }

        if (config.seedvrColorFix) {
            filters.push('url(#bxc-filter-seedvr-colorfix)');
        }

        if (!isHdrSupported && config.hdrMode !== 'off') {
            filters.push(`contrast(112%) saturate(120%) brightness(103%)`);
        }
        if (config.vidSaturation != 100) filters.push(`saturate(${config.vidSaturation}%)`);
        if (config.vidContrast != 100) filters.push(`contrast(${config.vidContrast}%)`);
        if (config.vidBrightness != 100) filters.push(`brightness(${config.vidBrightness}%)`);

        const filterVal = filters.join(' ');
        if (video.dataset.mjsAppliedFilter !== filterVal) {
            video.dataset.mjsAppliedFilter = filterVal;
            video.style.setProperty('filter', filterVal, 'important');
        }

        if (video.style.zIndex !== '1') {
            video.style.setProperty('z-index', '1', 'important');
        }
        if (video.style.backdropFilter !== 'none') {
            video.style.setProperty('backdrop-filter', 'none');
        }

        const currentStretchMode = `${config.videoStretch}_${config.stretchX}_${config.stretchY}_${config.aspectRatio}`;
        if (video.dataset.mjsAppliedStretch !== currentStretchMode) {
            video.dataset.mjsAppliedStretch = currentStretchMode;
            if (config.videoStretch === 'fill') {
                video.style.objectFit = 'fill';
                video.style.transform = 'none';
            } else if (config.videoStretch === 'custom') {
                video.style.objectFit = 'fill';
                video.style.transform = `scaleX(${config.stretchX}) scaleY(${config.stretchY})`;
            } else {
                video.style.objectFit = 'contain';
                video.style.transform = 'none';
                if (config.aspectRatio !== '16:9') {
                    video.style.aspectRatio = config.aspectRatio.replace(':', '/');
                }
            }
        }
    };

    // ==========================================
    // 8. OBSERVERS Y ESTADÍSTICAS MODULARES (OSD EN CAPA SUPREMA + BATERÍA REAL)
    // ==========================================
    let currentBatteryStr = '100%';
    if (navigator.getBattery) {
        navigator.getBattery().then(batt => {
            const updateBatt = () => {
                currentBatteryStr = `${Math.round(batt.level * 100)}%${batt.charging ? '⚡' : ''}`;
            };
            updateBatt();
            batt.addEventListener('levelchange', updateBatt);
            batt.addEventListener('chargingchange', updateBatt);
        }).catch(() => {});
    }

    let mouseTimer;
    if (config.hideIdleMouse) {
        document.addEventListener('mousemove', () => {
            document.documentElement.style.cursor = 'default';
            clearTimeout(mouseTimer);
            mouseTimer = setTimeout(() => { document.documentElement.style.cursor = 'none'; }, 2000);
        });
    }

    function initSlideHide() {
        if (!config.touchSlideHide) return;
        let startX;
        document.addEventListener('touchstart', e => { startX = e.touches[0].clientX; }, { passive: true });
        document.addEventListener('touchend', e => {
            if (!startX) return;
            let diffX = e.changedTouches[0].clientX - startX;
            if (Math.abs(diffX) > 60) {
                const canvas = document.getElementById('BabylonCanvasContainer-main');
                const surface = document.getElementById('MultiTouchSurface');
                const customTouch = document.getElementById('mjs-touch-overlay');
                const disp = diffX < 0 ? 'none' : 'block';
                if (canvas) canvas.style.display = disp;
                if (surface) surface.style.display = disp;
                if (customTouch) customTouch.style.display = disp;
            }
        }, { passive: true });
    }

    function setMachineFullScreen() {
        try {
            let el = document.documentElement;
            if (el.requestFullscreen) el.requestFullscreen();
            else if (el.webkitRequestFullscreen) el.webkitRequestFullscreen();
            if (screen?.orientation?.lock) screen.orientation.lock('landscape').catch(() => {});
        } catch (e) {}
    }

    if (config.disableUIAnim) {
        const style = document.createElement('style');
        style.innerHTML = `* { transition: none !important; animation: none !important; }`;
        document.head.appendChild(style);
    }

    function initStats() {
        const hud = ensureHudOnTopOfStream();
        if (!hud) return;

        if (config.showStats) {
            hud.style.display = 'flex';
            hud.className = `hud-${config.statsPosition} hud-layout-${config.osdLayout}`;
            hud.style.opacity = config.statsOpacity / 100;
            if (!hud.innerHTML) {
                hud.innerHTML = `<div class="osd-row"><span class="osd-label">⚡ Stream:</span><span>Activo (${getTargetDimensions().code})</span></div>`;
            }
        }

        if (statsIntervalId) return;

        statsIntervalId = setInterval(async () => {
            const currentHud = ensureHudOnTopOfStream();
            if (!currentHud) return;

            const activeVideo = document.querySelector('video.game-stream-video') || document.querySelector('#game-stream video');
            const isStreamActive = peerConnection && (peerConnection.connectionState === 'connected' || peerConnection.connectionState === 'connecting' || (activeVideo && !activeVideo.paused));

            if (!config.showStats || !isStreamActive) {
                currentHud.style.display = 'none';
                return;
            }
            currentHud.style.display = 'flex';
            currentHud.className = `hud-${config.statsPosition} hud-layout-${config.osdLayout}`;
            currentHud.style.opacity = config.statsOpacity / 100;

            const stats = await peerConnection.getStats();
            let fps = 0, rtt = 0, bitrate = 0, pl = 0, fl = 0, dl = 0, ul = 0, dt = 0, res = '0x0', jit = 0;
            const now = Date.now();

            stats.forEach(stat => {
                if (stat.type === 'inbound-rtp' && stat.kind === 'video') {
                    fps = stat.framesPerSecond || 0;
                    pl = stat.packetsLost || 0;
                    fl = stat.framesDropped || 0;
                    jit = stat.jitter || 0;
                    if (stat.frameWidth) res = `${stat.frameWidth}x${stat.frameHeight}`;
                    if (stat.totalDecodeTime && stat.framesDecoded) dt = (stat.totalDecodeTime / stat.framesDecoded) * 1000;
                    if (stat.bytesReceived) {
                        dl = (stat.bytesReceived / 1048576).toFixed(1);
                        if (window._bxcLastBytes) bitrate = ((stat.bytesReceived - window._bxcLastBytes) * 8) / ((now - window._bxcLastTime) * 1000);
                        window._bxcLastBytes = stat.bytesReceived; window._bxcLastTime = now;
                    }
                }
                if (stat.type === 'candidate-pair' && stat.state === 'succeeded') {
                    rtt = (stat.currentRoundTripTime || 0) * 1000;
                    if (stat.bytesSent) ul = (stat.bytesSent / 1048576).toFixed(1);
                }
            });

            let netType = '🌐 Red';
            try {
                const conn = navigator.connection || navigator.mozConnection || navigator.webkitConnection;
                if (conn) {
                    if (conn.type === 'wifi' || conn.effectiveType === '4g' && !conn.saveData) netType = '📶 Wi-Fi';
                    else if (conn.type === 'cellular' || ['slow-2g', '2g', '3g'].includes(conn.effectiveType)) netType = '📱 Datos';
                    else if (conn.type === 'ethernet') netType = '💻 Ethernet';
                    else netType = `📶 ${conn.effectiveType ? conn.effectiveType.toUpperCase() : 'Wi-Fi'}`;
                }
            } catch (e) {}

            let html = '';
            if (config.osdLayout === 'vertical') {
                html += `<div style="text-align:center; font-weight:bold; margin-bottom:3px; border-bottom:1px solid rgba(255,255,255,0.3); padding-bottom:2px; width:100%;">📊 OSD</div>`;
            }

            const addRow = (show, label, val) => { if (show) html += `<div class="osd-row"><span class="osd-label">${label}:</span><span>${val}</span></div>`; };

            addRow(config.osdTime, '⏱️ Reloj', new Date().toLocaleTimeString('es-ES', { hour: '2-digit', minute: '2-digit' }));
            addRow(config.osdPlay, '⏳ T. Jugado', `${Math.floor((now - sessionStartTime) / 60000)}m`);
            addRow(config.osdBatt, '🔋 Bat', currentBatteryStr);
            addRow(config.osdPing, '📶 Ping', `${Math.round(rtt)}ms`);
            addRow(config.osdJit, '〰️ Jitter', `${jit.toFixed(2)}s`);
            addRow(config.osdRes, '🖥️ Resol', res);
            addRow(config.osdFps, '🎞️ FPS', fps);
            addRow(config.osdBtr, '📈 Bitrate', `${bitrate.toFixed(1)}M`);
            addRow(config.osdDt, '⚙️ Decode', `${dt.toFixed(0)}ms`);
            addRow(config.osdPl, '📉 Loss', pl);
            addRow(config.osdFl, '🛑 Drop', fl);
            addRow(config.osdDl, '⬇️ DL', `${dl}M`);
            addRow(config.osdUl, '⬆️ UL', `${ul}M`);
            addRow(config.osdNet, '🌐 Red', netType);

            currentHud.innerHTML = html;

            // ⚡ Sincronizar métricas con el Monitor de Rendimiento Nativo para el Copiloto IA
            GodModeNativeBridge.updatePerformanceMetrics(
                Math.round(fps),
                Math.round(rtt),
                Math.round(jit * 1000),
                parseFloat(pl),
                Math.round(bitrate * 1000),
                parseFloat(dt)
            );
        }, 1000);
    }

    function initSystemObservers() {
        let sysThrottle = null;
        const observer = new MutationObserver(() => {
            if (sysThrottle) return;
            sysThrottle = setTimeout(() => {
                sysThrottle = null;

                if (config.showStats && wasInGameSession) {
                    ensureHudOnTopOfStream();
                }

                if (wasInGameSession) {
                    const streamVideo = document.querySelector('video.game-stream-video') || document.querySelector('#game-stream video') || document.querySelector('div[data-testid="media-container"] video');
                    
                    // Detect game title while in session
                    const streamTitle = document.querySelector('div[class*="StreamHeader-module__gameTitle"]');
                    if (streamTitle && streamTitle.innerText) {
                        GodModeNativeBridge.setCurrentGame(streamTitle.innerText.trim());
                    }

                    if (!streamVideo && (!peerConnection || peerConnection.connectionState !== 'connected')) {
                        wasInGameSession = false;
                        GodModeNativeBridge.setPointerCapture(false);
                        const hud = document.getElementById('bxc-hud');
                        if (hud) hud.style.display = 'none';
                        runPostGameCacheDetox(false);
                    }
                } else {
                    // Detect game title in the library/details page
                    const titleEl = document.querySelector('h1[class*="GameDetails-module__title"], div[class*="GameDetails-module__title"]');
                    if (titleEl && titleEl.innerText) {
                        GodModeNativeBridge.setCurrentGame(titleEl.innerText.trim());
                    }
                }

                if (config.antiKick) {
                    const btn = document.querySelector('button[data-auto-focus="true"]');
                    if (btn && btn.parentElement && btn.parentElement.children && btn.parentElement.children.length === 1) {
                        btn.click();
                    }
                }

                const streamMenuContainer = document.querySelector('div[class^="StreamMenu-module__menuContainer"] div[class^="Menu-module__scrollable"]');
                if (streamMenuContainer && !document.getElementById('mjs-stream-menu-btn')) {
                    const itemContainer = document.createElement('div');
                    const hudBtn = document.createElement('button');
                    hudBtn.id = 'mjs-stream-menu-btn';
                    hudBtn.className = 'mjs-native-hud-btn';
                    hudBtn.innerHTML = '⚙️ MJS';

                    hudBtn.onclick = (e) => {
                        e.preventDefault();
                        const ov = document.getElementById('bxc-overlay');
                        if (ov) ov.style.display = 'flex';
                        const backButton = document.querySelector('button[class^="StreamMenu-module__backButton"]');
                        if (backButton) backButton.click();
                    };

                    itemContainer.appendChild(hudBtn);
                    streamMenuContainer.appendChild(itemContainer);
                }
            }, 250);
        });
        if (document.body) {
            observer.observe(document.body, { childList: true, subtree: true });
        }

        document.addEventListener('click', (e) => {
            const btn = e.target.closest('button');
            if (!btn) return;
            const txt = (btn.innerText || btn.getAttribute('aria-label') || '').toLowerCase();
            const testId = (btn.getAttribute('data-testid') || '').toLowerCase();
            if (wasInGameSession && (testId.includes('quit') || txt.includes('salir del juego') || txt.includes('quit game') || txt.includes('disconnect'))) {
                wasInGameSession = false;
                GodModeNativeBridge.setPointerCapture(false);
                setTimeout(() => runPostGameCacheDetox(false), 600);
            }
        }, true);
    }

    // ==========================================
    // 9. MOTOR DE ENTRADA AVANZADO (POLLING RATE 4MS, DEADZONES, SWAP ABXY, EMULADOR M&K, CONTROLES TÁCTILES UNIVERSALES Y TV NAV)
    // ==========================================
    const origGetGamepads = navigator.getGamepads ? navigator.getGamepads.bind(navigator) : null;

    const virtualPadState = {
        active: false,
        buttons: Array.from({ length: 17 }, () => ({ pressed: false, touched: false, value: 0.0 })),
        axes: [0.0, 0.0, 0.0, 0.0]
    };

    // Función global expuesta para que el puente nativo de Android inyecte deltas de ratón físico capturado
    window.__injectNativeMouseDelta = function (dx, dy) {
        if (config.safeNativeModeOnly || (!config.enableMnKEmulation && !config.forceKeyboardConsole)) return;
        virtualPadState.active = true;
        const sens = 0.045;
        virtualPadState.axes[2] = Math.max(-1.0, Math.min(1.0, dx * sens));
        virtualPadState.axes[3] = Math.max(-1.0, Math.min(1.0, dy * sens));
        clearTimeout(window.__nativeMouseStopTimer);
        window.__nativeMouseStopTimer = setTimeout(() => {
            virtualPadState.axes[2] = 0.0;
            virtualPadState.axes[3] = 0.0;
        }, 25);
    };

    function applyRadialDeadzone(x, y, dzPercent) {
        if (!dzPercent || dzPercent <= 0) return [x, y];
        const dz = Math.min(0.95, dzPercent / 100);
        const mag = Math.hypot(x, y);
        if (mag < dz) return [0.0, 0.0];
        const norm = Math.min(1.0, (mag - dz) / (1.0 - dz));
        const scale = norm / mag;
        return [x * scale, y * scale];
    }

    function transformGamepad(pad) {
        if (!pad) return null;
        const buttons = pad.buttons.map(b => ({
            pressed: !!b.pressed,
            touched: !!b.touched,
            value: typeof b.value === 'number' ? b.value : (b.pressed ? 1.0 : 0.0)
        }));
        const axes = [...pad.axes];

        if (config.deadzoneL > 0 && axes.length >= 2) {
            const [lx, ly] = applyRadialDeadzone(axes[0], axes[1], config.deadzoneL);
            axes[0] = lx; axes[1] = ly;
        }
        if (config.deadzoneR > 0 && axes.length >= 4) {
            const [rx, ry] = applyRadialDeadzone(axes[2], axes[3], config.deadzoneR);
            axes[2] = rx; axes[3] = ry;
        }

        if (config.swapABXY && buttons.length >= 4) {
            const tmpA = buttons[0]; buttons[0] = buttons[1]; buttons[1] = tmpA;
            const tmpX = buttons[2]; buttons[2] = buttons[3]; buttons[3] = tmpX;
        }

        if (virtualPadState.active) {
            for (let i = 0; i < Math.min(buttons.length, virtualPadState.buttons.length); i++) {
                if (virtualPadState.buttons[i].pressed) {
                    buttons[i] = { ...virtualPadState.buttons[i] };
                }
            }
            for (let i = 0; i < 4; i++) {
                if (Math.abs(virtualPadState.axes[i]) > 0.01) {
                    axes[i] = Math.max(-1.0, Math.min(1.0, (axes[i] || 0) + virtualPadState.axes[i]));
                }
            }
        }

        return {
            id: pad.id || 'Xbox Series X Controller (XInput STANDARD GAMEPAD)',
            index: pad.index || 0,
            connected: true,
            timestamp: performance.now(),
            mapping: 'standard',
            axes: axes,
            buttons: buttons,
            vibrationActuator: pad.vibrationActuator
        };
    }

    if (origGetGamepads) {
        let cachedPads = [];
        let lastPollTimestamp = 0;

        navigator.getGamepads = function () {
            const now = performance.now();
            const minInterval = config.pollingRate || 4;
            if (now - lastPollTimestamp < minInterval && cachedPads.length > 0) {
                return cachedPads;
            }
            lastPollTimestamp = now;

            const rawPads = origGetGamepads();
            const result = [null, null, null, null];
            let hasPhysicalPad = false;

            for (let i = 0; i < rawPads.length; i++) {
                if (rawPads[i] && rawPads[i].connected) {
                    hasPhysicalPad = true;
                    const targetIdx = config.enableCoOp ? i : 0;
                    result[targetIdx] = transformGamepad(rawPads[i]);
                }
            }

            if (!hasPhysicalPad && (virtualPadState.active || (!config.safeNativeModeOnly && (config.enableMnKEmulation || config.forceKeyboardConsole)) || config.forceTouchVirtual)) {
                result[0] = transformGamepad({
                    id: 'Xbox Series X Controller (GodMode Virtual Gamepad)',
                    index: 0,
                    connected: true,
                    axes: [0.0, 0.0, 0.0, 0.0],
                    buttons: Array.from({ length: 17 }, () => ({ pressed: false, touched: false, value: 0.0 })),
                    vibrationActuator: null
                });
            }

            cachedPads = result;
            return result;
        };
    }

    // Emulación de Teclado y Ratón a Gamepad
    if (!config.safeNativeModeOnly && (config.enableMnKEmulation || config.forceKeyboardConsole)) {
        const keyMap = {
            'KeyW': { axis: 1, val: -1.0 },
            'KeyS': { axis: 1, val: 1.0 },
            'KeyA': { axis: 0, val: -1.0 },
            'KeyD': { axis: 0, val: 1.0 },
            'Space': { btn: 0 },
            'KeyC': { btn: 1 },
            'KeyR': { btn: 2 },
            'KeyF': { btn: 3 },
            'KeyQ': { btn: 4 },
            'KeyE': { btn: 5 },
            'ShiftLeft': { btn: 10 },
            'ControlLeft': { btn: 11 },
            'Enter': { btn: 9 },
            'Tab': { btn: 8 },
            'ArrowUp': { btn: 12 },
            'ArrowDown': { btn: 13 },
            'ArrowLeft': { btn: 14 },
            'ArrowRight': { btn: 15 }
        };
        const pressedKeys = new Set();

        const updateWASD = () => {
            virtualPadState.axes[0] = (pressedKeys.has('KeyD') ? 1 : 0) - (pressedKeys.has('KeyA') ? 1 : 0);
            virtualPadState.axes[1] = (pressedKeys.has('KeyS') ? 1 : 0) - (pressedKeys.has('KeyW') ? 1 : 0);
            if (virtualPadState.axes[0] !== 0 && virtualPadState.axes[1] !== 0) {
                virtualPadState.axes[0] *= 0.7071;
                virtualPadState.axes[1] *= 0.7071;
            }
        };

        window.addEventListener('keydown', (e) => {
            if (document.getElementById('bxc-overlay')?.style.display === 'flex') return;
            const m = keyMap[e.code];
            if (m) {
                virtualPadState.active = true;
                pressedKeys.add(e.code);
                if (m.btn !== undefined) virtualPadState.buttons[m.btn] = { pressed: true, touched: true, value: 1.0 };
                else updateWASD();
            }
        });

        window.addEventListener('keyup', (e) => {
            const m = keyMap[e.code];
            if (m) {
                pressedKeys.delete(e.code);
                if (m.btn !== undefined) virtualPadState.buttons[m.btn] = { pressed: false, touched: false, value: 0.0 };
                else updateWASD();
            }
        });

        window.addEventListener('mousemove', (e) => {
            if (document.pointerLockElement) {
                window.__injectNativeMouseDelta(e.movementX, e.movementY);
            }
        });

        window.addEventListener('mousedown', (e) => {
            if (document.getElementById('bxc-overlay')?.style.display === 'flex') return;
            if (e.button === 0) virtualPadState.buttons[7] = { pressed: true, touched: true, value: 1.0 };
            if (e.button === 2) virtualPadState.buttons[6] = { pressed: true, touched: true, value: 1.0 };
        });

        window.addEventListener('mouseup', (e) => {
            if (e.button === 0) virtualPadState.buttons[7] = { pressed: false, touched: false, value: 0.0 };
            if (e.button === 2) virtualPadState.buttons[6] = { pressed: false, touched: false, value: 0.0 };
        });
    }

    // Generador de Controles Táctiles Universales en Pantalla (forceTouchVirtual)
    function injectUniversalTouchOverlay() {
        if (!config.forceTouchVirtual || document.getElementById('mjs-touch-overlay') || !document.body) return;

        const touchLayer = document.createElement('div');
        touchLayer.id = 'mjs-touch-overlay';
        touchLayer.style.cssText = 'position:fixed; inset:0; pointer-events:none; z-index:2147483640; user-select:none; -webkit-user-select:none; touch-action:none;';

        const createBtn = (label, btnIdx, css) => {
            const b = document.createElement('div');
            b.innerText = label;
            b.style.cssText = `position:absolute; display:flex; align-items:center; justify-content:center; border-radius:50%; background:rgba(25,25,25,0.45); backdrop-filter:blur(8px); border:1.5px solid rgba(255,255,255,0.3); color:#fff; font-family:'Segoe UI',sans-serif; font-weight:bold; pointer-events:auto; touch-action:none; ${css}`;
            b.addEventListener('pointerdown', (e) => {
                e.preventDefault();
                b.setPointerCapture(e.pointerId);
                b.style.background = 'rgba(255,255,255,0.35)';
                virtualPadState.active = true;
                virtualPadState.buttons[btnIdx] = { pressed: true, touched: true, value: 1.0 };
            });
            const release = (e) => {
                e.preventDefault();
                b.style.background = 'rgba(255,255,255,0.45)';
                virtualPadState.buttons[btnIdx] = { pressed: false, touched: false, value: 0.0 };
            };
            b.addEventListener('pointerup', release);
            b.addEventListener('pointercancel', release);
            return b;
        };

        touchLayer.appendChild(createBtn('A', 0, 'width:58px; height:58px; bottom:55px; right:95px;'));
        touchLayer.appendChild(createBtn('B', 1, 'width:58px; height:58px; bottom:115px; right:35px;'));
        touchLayer.appendChild(createBtn('X', 2, 'width:58px; height:58px; bottom:115px; right:155px;'));
        touchLayer.appendChild(createBtn('Y', 3, 'width:58px; height:58px; bottom:175px; right:95px;'));
        touchLayer.appendChild(createBtn('LB', 4, 'width:70px; height:42px; border-radius:10px; top:90px; left:35px;'));
        touchLayer.appendChild(createBtn('LT', 6, 'width:70px; height:42px; border-radius:10px; top:35px; left:35px;'));
        touchLayer.appendChild(createBtn('RB', 5, 'width:70px; height:42px; border-radius:10px; top:90px; right:35px;'));
        touchLayer.appendChild(createBtn('RT', 7, 'width:70px; height:42px; border-radius:10px; top:35px; right:35px;'));
        touchLayer.appendChild(createBtn('☰', 9, 'width:40px; height:40px; top:25px; right:44%; font-size:14px;'));
        touchLayer.appendChild(createBtn('⧉', 8, 'width:40px; height:40px; top:25px; left:44%; font-size:14px;'));

        const createStick = (axisXIdx, axisYIdx, cssPos) => {
            const base = document.createElement('div');
            base.style.cssText = `position:absolute; width:130px; height:130px; border-radius:50%; background:rgba(20,20,20,0.35); border:1.5px solid rgba(255,255,255,0.22); pointer-events:auto; touch-action:none; ${cssPos}`;
            const stick = document.createElement('div');
            stick.style.cssText = 'position:absolute; width:54px; height:54px; left:38px; top:38px; border-radius:50%; background:rgba(255,255,255,0.45); box-shadow:0 4px 12px rgba(0,0,0,0.5); pointer-events:none;';
            base.appendChild(stick);

            let activeId = null;
            const maxRadius = 48;

            base.addEventListener('pointerdown', (e) => {
                activeId = e.pointerId;
                base.setPointerCapture(activeId);
                virtualPadState.active = true;
            });
            base.addEventListener('pointermove', (e) => {
                if (e.pointerId !== activeId) return;
                const rect = base.getBoundingClientRect();
                const cx = rect.left + rect.width / 2;
                const cy = rect.top + rect.height / 2;
                let dx = e.clientX - cx;
                let dy = e.clientY - cy;
                const dist = Math.hypot(dx, dy);
                if (dist > maxRadius) {
                    dx = (dx / dist) * maxRadius;
                    dy = (dy / dist) * maxRadius;
                }
                stick.style.transform = `translate3d(${dx}px, ${dy}px, 0)`;
                virtualPadState.axes[axisXIdx] = parseFloat((dx / maxRadius).toFixed(3));
                virtualPadState.axes[axisYIdx] = parseFloat((dy / maxRadius).toFixed(3));
            });
            const resetStick = (e) => {
                if (e.pointerId !== activeId) return;
                activeId = null;
                stick.style.transform = 'translate3d(0,0,0)';
                virtualPadState.axes[axisXIdx] = 0.0;
                virtualPadState.axes[axisYIdx] = 0.0;
            };
            base.addEventListener('pointerup', resetStick);
            base.addEventListener('pointercancel', resetStick);
            return base;
        };

        touchLayer.appendChild(createStick(0, 1, 'bottom:45px; left:45px;'));
        touchLayer.appendChild(createStick(2, 3, 'bottom:45px; right:235px; width:110px; height:110px;'));

        document.body.appendChild(touchLayer);

        if (config.autoHideTouch) {
            window.addEventListener('gamepadconnected', () => { touchLayer.style.display = 'none'; });
            window.addEventListener('gamepaddisconnected', () => { touchLayer.style.display = 'block'; });
        }
    }

    // Navegación espacial en el panel #bxc-panel para Android TV, Mandos y LG webOS
    let currentFocusIdx = 0;
    function getFocusablePanelElements() {
        const overlay = document.getElementById('bxc-overlay');
        if (!overlay || overlay.style.display !== 'flex') return [];
        const activeContent = overlay.querySelector('.bxc-content.active');
        const tabs = Array.from(overlay.querySelectorAll('.bxc-tab'));
        const controls = activeContent ? Array.from(activeContent.querySelectorAll('select, input, button')) : [];
        const footerBtns = Array.from(overlay.querySelectorAll('.bxc-footer button'));
        return [...tabs, ...controls, ...footerBtns].filter(el => el.offsetParent !== null && !el.disabled);
    }

    function highlightFocusedElement(elements, idx) {
        elements.forEach((el, i) => {
            const row = el.closest('.bxc-row');
            if (i === idx) {
                el.focus();
                if (row) {
                    row.classList.add('nav-focused');
                    row.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
                }
            } else if (row) {
                row.classList.remove('nav-focused');
            }
        });
    }

    window.addEventListener('keydown', (e) => {
        const overlay = document.getElementById('bxc-overlay');
        const isPanelOpen = overlay && overlay.style.display === 'flex';

        if (e.key === 'F8' || e.keyCode === 405 || e.keyCode === 406) {
            e.preventDefault();
            if (overlay) {
                overlay.style.display = isPanelOpen ? 'none' : 'flex';
                if (!isPanelOpen) {
                    currentFocusIdx = 0;
                    highlightFocusedElement(getFocusablePanelElements(), 0);
                }
            }
            return;
        }

        if (!isPanelOpen) return;
        const elements = getFocusablePanelElements();
        if (!elements.length) return;

        if (e.key === 'ArrowDown' || e.keyCode === 40) {
            e.preventDefault();
            currentFocusIdx = (currentFocusIdx + 1) % elements.length;
            highlightFocusedElement(elements, currentFocusIdx);
        } else if (e.key === 'ArrowUp' || e.keyCode === 38) {
            e.preventDefault();
            currentFocusIdx = (currentFocusIdx - 1 + elements.length) % elements.length;
            highlightFocusedElement(elements, currentFocusIdx);
        } else if (e.key === 'Enter' || e.keyCode === 13) {
            const activeEl = elements[currentFocusIdx];
            if (activeEl && activeEl.type === 'checkbox') {
                e.preventDefault();
                activeEl.checked = !activeEl.checked;
                activeEl.dispatchEvent(new Event('change'));
            } else if (activeEl && activeEl.tagName === 'BUTTON') {
                e.preventDefault();
                activeEl.click();
            }
        } else if (e.key === 'Escape' || e.keyCode === 461) {
            e.preventDefault();
            overlay.style.display = 'none';
        }
    });

})();
