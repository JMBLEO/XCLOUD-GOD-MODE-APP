package com.aistudio.xcloudgodmode.ai.streaming

import android.webkit.WebView
import com.aistudio.xcloudgodmode.ai.models.SettingsModel

/**
 * ScriptInjector implements the "God Mode" engine by injecting JavaScript into the Xbox Cloud Gaming WebView.
 */
class ScriptInjector(private val context: android.content.Context) {

    fun setupDocumentStartInjection(webView: WebView) {
        // Reservado para inyecciones previas al DOM
    }

    fun injectGodModeScript(webView: WebView, settings: SettingsModel = SettingsModel()) {
        val targetCodec = when (settings.videoCodec) {
            "AV1" -> "AV1"
            "HEVC / H.265" -> "H265"
            "VP9" -> "VP9"
            "H.264 High" -> "H264"
            else -> "H264"
        }
        val maxBitrateKbps = settings.maxBitrateMbps * 1000
        val audioGain = settings.volumeBoostPercent / 100.0f
        val fsrSharpness = settings.fsrSharpness
        val isMono = settings.audioChannelMode == "Mono Mix"
        val upscaler = settings.upscalerEngine
        
        val esrganFilter = if (upscaler.contains("ESRGAN")) "url(#gm-esrgan-srvgg)" else ""
        val seedvrFilter = if (settings.seedvrColorFix) "url(#gm-seedvr-colorfix)" else ""

        val payload = """
            (function() {
                if (window.__GOD_MODE_INJECTED__) {
                    console.log('[GodMode] Configuración actualizada.');
                    if (window.__updateGodModeSettings) {
                        window.__updateGodModeSettings($audioGain, $fsrSharpness, "$upscaler");
                    }
                    return;
                }
                window.__GOD_MODE_INJECTED__ = true;

                const svgFilters = document.createElementNS("http://www.w3.org/2000/svg", "svg");
                svgFilters.style.cssText = "position:absolute;width:0;height:0;pointer-events:none;";
                svgFilters.innerHTML = `
                    <defs>
                        <filter id="gm-esrgan-srvgg" color-interpolation-filters="sRGB">
                            <feGaussianBlur in="SourceGraphic" stdDeviation="0.35" result="denoise" />
                            <feComposite in="SourceGraphic" in2="denoise" operator="arithmetic" k1="0" k2="0.75" k3="0.25" result="base" />
                            <feConvolveMatrix in="base" order="3" preserveAlpha="true" kernelMatrix="-0.15 -0.35 -0.15 -0.35 3.0 -0.35 -0.15 -0.35 -0.15" />
                        </filter>
                        <filter id="gm-seedvr-colorfix" color-interpolation-filters="sRGB">
                            <feColorMatrix type="matrix" values="1.06 -0.03 -0.03 0 0.005  -0.02 1.05 -0.03 0 0.005  -0.03 -0.02 1.05 0 0.005  0 0 0 1 0" />
                        </filter>
                    </defs>
                `;
                document.body.appendChild(svgFilters);

                const style = document.createElement('style');
                style.id = 'xcloud-godmode-styles';
                style.innerHTML = `
                    header, nav, [data-tid="navigation-header"], [class*="HeaderBar"], [class*="NavigationContainer"], [class*="BottomBar"] {
                        display: none !important;
                        visibility: hidden !important;
                        opacity: 0 !important;
                        height: 0 !important;
                        pointer-events: none !important;
                    }
                    body, html {
                        margin: 0 !important;
                        padding: 0 !important;
                        background-color: #000000 !important;
                        width: 100% !important;
                        height: 100% !important;
                        overflow: hidden !important;
                    }
                    #game-stream, video, canvas, [class*="StreamContainer"] {
                        display: block !important;
                        visibility: visible !important;
                        opacity: 1 !important;
                        width: 100% !important;
                        height: 100% !important;
                        position: fixed !important;
                        inset: 0 !important;
                        background: #000 !important;
                    }
                    .godmode-enhanced {
                        filter: brightness(${settings.brightness}) contrast(${settings.contrast}) saturate(${settings.saturation}) $esrganFilter $seedvrFilter !important;
                    }
                    .fsr-rcas-active {
                        image-rendering: pixelated !important;
                    }
                `;
                document.head.appendChild(style);

                const origSetRemoteDesc = RTCPeerConnection.prototype.setRemoteDescription;
                RTCPeerConnection.prototype.setRemoteDescription = function(desc) {
                    try {
                        if (desc && desc.sdp) {
                            let sdp = desc.sdp;
                            const lines = sdp.split('\r\n');
                            const mVideoIdx = lines.findIndex(l => l.startsWith('m=video '));
                            if (mVideoIdx !== -1) {
                                lines.splice(mVideoIdx + 1, 0, 'b=AS:$maxBitrateKbps');
                            }
                            sdp = lines.map(line => {
                                if (line.startsWith('a=fmtp:111 ')) {
                                    return line + ';stereo=1;sprop-stereo=1;maxaveragebitrate=510000;cbr=1';
                                }
                                return line;
                            }).join('\r\n');
                            desc = new RTCSessionDescription({ type: desc.type, sdp: sdp });
                        }
                    } catch(err) { console.warn('[GodMode] SDP error:', err); }
                    return origSetRemoteDesc.apply(this, arguments);
                };

                let audioCtx = null;
                let gainNode = null;

                function hookAudio(videoElem) {
                    if (videoElem.__godModeAudio) return;
                    videoElem.__godModeAudio = true;
                    videoElem.classList.add('godmode-enhanced');
                    if ("$upscaler".includes("FSR")) videoElem.classList.add('fsr-rcas-active');

                    try {
                        const AudioContextClass = window.AudioContext || window.webkitAudioContext;
                        if (AudioContextClass) {
                            audioCtx = new AudioContextClass();
                            const source = audioCtx.createMediaElementSource(videoElem);
                            gainNode = audioCtx.createGain();
                            gainNode.gain.value = $audioGain;
                            source.connect(gainNode);
                            gainNode.connect(audioCtx.destination);
                        }
                    } catch(e) { console.warn('[GodMode] Audio Hook:', e); }
                }

                const observer = new MutationObserver(() => {
                    const video = document.querySelector('video');
                    if (video) hookAudio(video);
                });
                observer.observe(document.body || document.documentElement, { childList: true, subtree: true });

                window.__updateGodModeSettings = function(newGain, newSharpness, newUpscaler) {
                    if (gainNode) gainNode.gain.value = newGain;
                };

                console.log('[GodMode] Motor PlayStaxBox Híbrido Activo.');
            })();
        """.trimIndent()

        val fullScript = "javascript:(function() { try { $payload } catch(e) { console.error('[GodMode JS Error]', e); } })();"
        webView.evaluateJavascript(fullScript, null)
    }

    companion object {
        fun injectGodModeScript(webView: WebView, settings: SettingsModel = SettingsModel()) {
            val injector = ScriptInjector(webView.context)
            injector.injectGodModeScript(webView, settings)
        }
    }
}
