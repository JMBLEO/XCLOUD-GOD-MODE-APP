package com.xcloud.godmode

import com.xcloud.godmode.audio.AudioRumbleAnalyzer
import com.xcloud.godmode.engine.*
import com.xcloud.godmode.haptics.*
import com.xcloud.godmode.input.TouchProjectionMatrix
import com.xcloud.godmode.input.VirtualKeyboardDialogHandler
import com.xcloud.godmode.models.CodecPreference
import com.xcloud.godmode.network.*
import com.xcloud.godmode.streaming.LowLatencyMediaCodecConfigurator
import com.xcloud.godmode.streaming.TizenDeviceSpoofing
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.nio.ByteBuffer

class ComprehensiveArchitectureComponentsTest {

    @Test
    fun testJoseSignatureServiceGeneratesValidPoPHeaders() {
        val result = JoseSignatureService.generatePopSignature(
            method = "GET",
            path = "/v2/login/user",
            authorizationToken = "Bearer test_token_xyz",
            createdSeconds = 1700000000L
        )

        assertNotNull(result)
        assertTrue(result.signatureInputHeader.contains("sig1=(\"@method\" \"@path\" \"authorization\")"))
        assertTrue(result.signatureInputHeader.contains("created=1700000000"))
        assertTrue(result.signatureInputHeader.contains("alg=\"ES256\""))
        assertTrue(result.signatureHeader.startsWith("sig1=:"))
        assertTrue(result.signatureHeader.endsWith(":"))
        assertNotNull(result.keyId)
        assertTrue(result.keyId.isNotBlank())
    }

    @Test
    fun testDatacenterRegistryContains19AzureRegions() {
        assertEquals(19, DatacenterRegistry.ALL_REGIONS.size)
        val regionIds = DatacenterRegistry.ALL_REGIONS.map { it.id }.toSet()
        assertTrue(regionIds.contains("chilecentral"))
        assertTrue(regionIds.contains("brazilsouth"))
        assertTrue(regionIds.contains("eastus"))
        assertTrue(regionIds.contains("westus2"))
        assertTrue(regionIds.contains("uksouth"))
        assertTrue(regionIds.contains("japaneast"))
    }

    @Test
    fun testPreFlightQueueInterceptorCrowdedDetection() {
        val provider = DatacenterTelemetryProvider()
        val noQueue = PreFlightQueueInterceptor.evaluateQueue(0, "chilecentral", provider)
        assertTrue(noQueue is PreFlightQueueStatus.NoQueue)

        val acceptable = PreFlightQueueInterceptor.evaluateQueue(60, "chilecentral", provider)
        assertTrue(acceptable is PreFlightQueueStatus.QueueAcceptable)
        assertEquals(60, (acceptable as PreFlightQueueStatus.QueueAcceptable).estimatedWaitSeconds)

        val crowded = PreFlightQueueInterceptor.evaluateQueue(180, "chilecentral", provider)
        assertTrue(crowded is PreFlightQueueStatus.QueueCrowded)
        assertEquals(180, (crowded as PreFlightQueueStatus.QueueCrowded).estimatedWaitSeconds)
    }

    @Test
    fun testTizenDeviceSpoofingGeneratesSamsungSmartTvPayload() {
        val payload = TizenDeviceSpoofing.buildDeviceInfoPayload(isHdrSupported = true)
        assertTrue(payload.contains("\"osName\":\"Tizen\""))
        assertTrue(payload.contains("\"make\":\"Samsung\""))
        assertTrue(payload.contains("\"model\":\"SmartTV\""))
        assertTrue(payload.contains("\"screenResolution\":\"3840x2160\""))
        assertEquals(TizenDeviceSpoofing.TIZEN_USER_AGENT, TizenDeviceSpoofing.TIZEN_USER_AGENT)
    }

    @Test
    fun testDualSenseHapticDriverBuildsValidUsbAndBluetoothReports() {
        val triggerEffect = AdaptiveTriggerEffect(
            type = TriggerEffectType.SECTION_SNAP,
            startPosition = 30,
            endPosition = 180,
            forceStrength = 200
        )

        val usbReport = DualSenseHapticDriver.buildUsbReport(
            leftTrigger = triggerEffect,
            rightTrigger = triggerEffect,
            rumbleLeft = 128,
            rumbleRight = 64
        )
        assertEquals(48, usbReport.size)
        assertEquals(0x02.toByte(), usbReport[0])

        val btReport = DualSenseHapticDriver.buildBluetoothReport(
            leftTrigger = triggerEffect,
            rightTrigger = triggerEffect,
            rumbleLeft = 128,
            rumbleRight = 64
        )
        assertEquals(64, btReport.size)
        assertEquals(0x31.toByte(), btReport[0])
    }

    @Test
    fun testUsbImpulseTriggerDriverAssembly() {
        val driver = UsbImpulseTriggerDriver()
        driver.isLeftRightInverted = true

        val report = driver.buildXboxImpulseReport(
            leftTriggerRumble = 0.8f,
            rightTriggerRumble = 0.2f,
            leftGripRumble = 0.5f,
            rightGripRumble = 0.5f
        )

        assertEquals(9, report.size)
        assertEquals(0x09.toByte(), report[0])
        // Due to inversion: LT receives 20, RT receives 80
        assertEquals(20.toByte(), report[3])
        assertEquals(80.toByte(), report[4])
    }

    @Test
    fun testKbmEmulatorMapsWasdAndButtons() {
        val kbm = KbmEmulator()
        kbm.handleKey(android.view.KeyEvent.KEYCODE_W, true)
        assertTrue(kbm.state.leftStickY > 0)

        kbm.handleKey(android.view.KeyEvent.KEYCODE_SPACE, true)
        assertTrue(kbm.state.buttonA)

        kbm.handleMouseButton(0, true)
        assertEquals(1.0f, kbm.state.rightTrigger, 0.001f)
    }

    @Test
    fun testTouchProjectionMatrixMapping() {
        val matrix = TouchProjectionMatrix()
        matrix.updateScreenDimensions(1920f, 1080f)
        val center = matrix.mapToStreamViewport(960f, 540f)
        assertNotNull(center)
        assertEquals(0.5f, center!!.first, 0.01f)
        assertEquals(0.5f, center.second, 0.01f)
    }

    @Test
    fun testTeredoTunnelDeobfuscation() {
        // Teredo IPv6: 2001:0000: ...
        val teredoBytes = ByteArray(16)
        teredoBytes[0] = 0x20.toByte()
        teredoBytes[1] = 0x01.toByte()
        teredoBytes[2] = 0x00.toByte()
        teredoBytes[3] = 0x00.toByte()

        // Obfuscate port 3074 (0x0C02) XOR 0xFFFF = 0xF3FD
        teredoBytes[10] = 0xF3.toByte()
        teredoBytes[11] = 0xFD.toByte()

        // Obfuscate 192.168.1.100 (C0.A8.01.64) XOR 0xFF = 3F.57.FE.9B
        teredoBytes[12] = 0x3F.toByte()
        teredoBytes[13] = 0x57.toByte()
        teredoBytes[14] = 0xFE.toByte()
        teredoBytes[15] = 0x9B.toByte()

        val resolved = TeredoTunnelService.deobfuscateClientAddress(teredoBytes)
        assertNotNull(resolved)
        assertEquals(3074, resolved!!.second)
        assertEquals("192.168.1.100", resolved.first.hostAddress)
    }

    @Test
    fun testAudioRumbleAnalyzerDetectsHighBass() {
        var triggeredCount = 0
        val analyzer = AudioRumbleAnalyzer(rumbleThresholdRms = 0.2f) {
            triggeredCount++
        }

        // Generate high amplitude PCM buffer (simulating an explosion)
        val loudPcm = ByteArray(1024)
        for (i in loudPcm.indices step 2) {
            val sample = 28000
            loudPcm[i] = (sample and 0xFF).toByte()
            loudPcm[i + 1] = ((sample shr 8) and 0xFF).toByte()
        }

        analyzer.analyzeChunk(loudPcm, loudPcm.size)
        assertTrue(triggeredCount > 0)
    }

    @Test
    fun testVirtualKeyboardDialogHandlerFlow() {
        var submittedText = ""
        val handler = VirtualKeyboardDialogHandler(
            onTextSubmitted = { _, text -> submittedText = text },
            onDialogCancelled = {}
        )

        handler.onShowTextDialogReceived("dialog_1", "Nombre de Personaje", "Introduce el nombre:")
        assertNotNull(handler.activeDialog.value)
        assertEquals("Nombre de Personaje", handler.activeDialog.value?.title)

        handler.submitText("MasterChief117")
        assertNull(handler.activeDialog.value)
        assertEquals("MasterChief117", submittedText)
    }

    @Test
    fun testConsoleSessionManagerSilentPowerOnPayload() {
        val manager = ConsoleSessionManager()
        manager.powerMode = ConsolePowerMode.SILENT_DIRECT_TO_STREAM

        val console = ConsoleDeviceInfo(
            liveId = "FD0012345678",
            consoleName = "Xbox Series X",
            localIp = "192.168.1.50",
            isOnline = true
        )

        val payload = manager.buildConnectPayload(console)
        assertEquals(true, payload["silentPowerOn"])
        assertEquals("192.168.1.50", payload["serverPath"])
        assertEquals("IPv6", payload["ipPreference"])
    }
}
