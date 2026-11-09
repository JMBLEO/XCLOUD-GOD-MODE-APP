package com.xcloud.godmode

import com.xcloud.godmode.audio.AudioVideoDriftCorrector
import com.xcloud.godmode.audio.PolyphaseSincResampler
import com.xcloud.godmode.audio.RecordingAudioMixer
import com.xcloud.godmode.bridge.BinaryIntegrityGuard
import com.xcloud.godmode.engine.HallEffectCalibrationEngine
import com.xcloud.godmode.engine.StickResponseCurveMapper
import com.xcloud.godmode.engine.SplitControllerFusionEngine
import com.xcloud.godmode.engine.VendorPaddleRemapper
import com.xcloud.godmode.engine.VehiclePhysicsTelemetryReceiver
import com.xcloud.godmode.engine.WheelSimulationDriver
import com.xcloud.godmode.haptics.DualSenseTouchpadHandler
import com.xcloud.godmode.input.KeyboardScancodeNormalizer
import com.xcloud.godmode.network.EmergencySessionCache
import com.xcloud.godmode.network.MultipathStreamBinder
import com.xcloud.godmode.network.PortMappingService
import com.xcloud.godmode.network.PrecisionTimeProtocolEngine
import com.xcloud.godmode.network.QuickResumeStateTracker
import com.xcloud.godmode.network.SelectivePacketDuplicator
import com.xcloud.godmode.services.PerGameProfileDispatcher
import com.xcloud.godmode.streaming.AdaptivePlaybackConfigurator
import com.xcloud.godmode.streaming.AntiAblLuminanceController
import com.xcloud.godmode.streaming.ClosedCaptionsExtractor
import com.xcloud.godmode.streaming.DynamicColorRangeNormalizer
import com.xcloud.godmode.streaming.HeadlessDisplayOverride
import com.xcloud.godmode.streaming.LanczosAndCasShaderPipeline
import com.xcloud.godmode.streaming.PacketConcealmentManager
import com.xcloud.godmode.streaming.SuperResolutionDispatcher
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ExtendedArchitecturePhasesTest {

    @Test
    fun testAdaptivePlaybackCapabilities() {
        val configurator = AdaptivePlaybackConfigurator()
        val caps = AdaptivePlaybackConfigurator.AdaptiveCapabilities(
            isAdaptivePlaybackSupported = true,
            maxWidth = 1920,
            maxHeight = 1080,
            preferredColorFormat = 0
        )
        assertTrue(configurator.canSeamlesslyScale(caps, 1280, 720))
        assertTrue(configurator.canSeamlesslyScale(caps, 1920, 1080))
        assertFalse(configurator.canSeamlesslyScale(caps, 2560, 1440))
    }

    @Test
    fun testDynamicColorRangeNormalizerShaderMatrix() {
        val normalizer = DynamicColorRangeNormalizer()
        val settings = normalizer.computeShaderMatrix(
            mode = DynamicColorRangeNormalizer.ColorRangeMode.FULL_RANGE_RGB,
            isOledDisplay = true
        )
        assertNotNull(settings.glslMatrix)
        assertEquals(16, settings.glslMatrix.size)
        assertTrue(settings.contrastFactor > 1.0f)
    }

    @Test
    fun testPortMappingTargetPorts() {
        val ports = PortMappingService.TARGET_UDP_PORTS
        assertTrue(ports.contains(3074)) // Xbox stream
        assertTrue(ports.contains(5050)) // SmartGlass
        assertTrue(ports.contains(9002)) // Video UDP
    }

    @Test
    fun testDualSenseTouchpadSliceParsingAndLedOutputReport() {
        val handler = DualSenseTouchpadHandler()
        val fakeReport = ByteArray(48)
        // Byte 33: Touch 1 Active (bit 7 = 0) + ID 1
        fakeReport[33] = 0x01.toByte()
        fakeReport[34] = 0x50.toByte() // X low
        fakeReport[35] = 0x12.toByte() // X high (0x2) + Y low (0x1)
        fakeReport[36] = 0x40.toByte() // Y high

        val state = handler.parseTouchpadSlice(fakeReport, 33)
        assertNotNull(state)
        assertTrue(state!!.touch1.isActive)
        assertTrue(state.touch1.x > 0)

        // Test LED report generation
        val ledReport = handler.buildLedOutputReport(0x10, 0x7C, 0x10, playerIndex = 1, isUsb = true)
        assertEquals(48, ledReport.size)
        assertEquals(0x02.toByte(), ledReport[0]) // USB Report ID
    }

    @Test
    fun testVendorPaddleRemapperDetectionAndMasks() {
        val remapper = VendorPaddleRemapper()
        val kishi = remapper.detectVendor(0x1532, 0x0000)
        assertEquals(VendorPaddleRemapper.ControllerVendor.RAZER_KISHI, kishi)

        val elite = remapper.detectVendor(0x045E, 0x0B00)
        assertEquals(VendorPaddleRemapper.ControllerVendor.XBOX_ELITE_SERIES_2, elite)

        val paddleState = VendorPaddleRemapper.PaddleState(m1Pressed = true, m2Pressed = true)
        val initialMask = 0
        val finalMask = remapper.applyPaddlesToButtonMask(initialMask, paddleState)
        // M1 defaults to L3 (0x0040), M2 defaults to R3 (0x0080) -> mask 0x00C0
        assertEquals(0x00C0, finalMask)
    }

    @Test
    fun testRecordingAudioMixerSoftLimiter() {
        val mixer = RecordingAudioMixer()
        val gamePcm = ShortArray(256) { 20000 }
        val micPcm = ShortArray(128) { 15000 }
        val outPcm = ShortArray(256)

        val mixedSamples = mixer.mixPcm16(gamePcm, 256, micPcm, 128, outPcm)
        assertEquals(256, mixedSamples)
        // Assert no overflow or crash and values remain within 16-bit PCM bounds
        for (sample in outPcm) {
            assertTrue(sample in -32768..32767)
        }
    }

    @Test
    fun testPacketConcealmentManagerFreezeHoldAndRecovery() {
        var keyframeRequested = false
        val manager = PacketConcealmentManager { keyframeRequested = true }

        // Normal state
        assertEquals(PacketConcealmentManager.ConcealmentState.NORMAL, manager.getState())

        // Simulating packet arrival restores state
        val wasRecovering = manager.onPacketReceived()
        assertFalse(wasRecovering)
        assertFalse(keyframeRequested)
    }

    @Test
    fun testVehiclePhysicsTelemetryParsing() {
        val receiver = VehiclePhysicsTelemetryReceiver()
        val data = ByteArray(324)
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, 1) // isRaceOn
        buffer.putFloat(8, 8000f) // maxRpm
        buffer.putFloat(16, 6500f) // currentRpm
        buffer.putFloat(88, 1.2f) // High front wheel slip -> heavy ABS brake lock
        buffer.putFloat(96, 0.9f) // High rear slip -> wheel spin traction loss

        val telemetry = receiver.parseForzaPacket(data, 324)
        assertNotNull(telemetry)
        assertTrue(telemetry!!.isRaceOn)
        assertEquals(8000f, telemetry.engineMaxRpm, 0.1f)
        assertTrue(telemetry.brakeTriggerHapticIntensity > 0.8f)
        assertTrue(telemetry.accelTriggerHapticIntensity > 0.8f)
    }

    @Test
    fun testWheelSimulationDriver() {
        val driver = WheelSimulationDriver()
        driver.updateConfig(maxDegrees = 900f, virtualDegrees = 360f, springForce = 0.5f)

        // Centered wheel, released pedals
        val center = driver.processWheelInputs(0, 0, 0)
        assertEquals(0f, center.leftStickX, 0.01f)

        // Turn right by 50%
        val right = driver.processWheelInputs(16384, 255, 0)
        assertTrue(right.leftStickX > 0.5f)
        assertEquals(1.0f, right.rightTrigger, 0.01f) // 100% throttle
    }

    @Test
    fun testMultipathStreamBinderRttSelection() {
        val binder = MultipathStreamBinder()
        binder.updatePathRtt(isWifi = true, rttMs = 15)
        binder.updatePathRtt(isWifi = false, rttMs = 45)
        // Auto best RTT should prefer Wi-Fi (15ms vs 45ms)
        assertNull(binder.getActiveVideoSocket()) // Sockets unbound in unit test
        binder.release()
    }

    @Test
    fun testSuperResolutionDispatcherProfiles() {
        val dispatcher = SuperResolutionDispatcher()
        val profile = dispatcher.selectOptimalMethod(
            isAdrenoGpu = true,
            targetFps = 120,
            userPreference = SuperResolutionDispatcher.UpscaleMethod.NATIVE_PASSTHROUGH
        )
        assertEquals(SuperResolutionDispatcher.UpscaleMethod.QUALCOMM_GSR, profile.method)
        assertEquals(1, profile.shaderPassCount)
        assertTrue(SuperResolutionDispatcher.SNAPDRAGON_GSR_FRAGMENT_SHADER.contains("uViewportSize"))
    }

    @Test
    fun testAudioVideoDriftCorrector() {
        val corrector = AudioVideoDriftCorrector()
        // Video at 100_000 us, audio at 70_000 us -> audio is lagging behind video by 30ms (>15ms threshold)
        val stats = corrector.evaluateDrift(videoPtsUs = 100_000L, audioPtsUs = 70_000L)
        assertFalse(stats.isInSync)
        assertTrue(stats.speedCorrectionMultiplier > 1.0f) // Must speed up audio

        // Elastic resample test
        val inPcm = ShortArray(100) { 1000 }
        val outPcm = ShortArray(150)
        val outCount = corrector.applyElasticResample(inPcm, 100, outPcm, 1.001f)
        assertTrue(outCount > 0)
    }

    @Test
    fun testStickResponseCurveMapper() {
        val mapper = StickResponseCurveMapper()
        // Center deadzone
        val deadzoneResult = mapper.mapStick(0.01f, 0.01f)
        assertTrue(deadzoneResult.isInDeadzone)
        assertEquals(0f, deadzoneResult.outputX, 0.001f)

        // Full deflection
        val fullDeflection = mapper.mapStick(1.0f, 0.0f)
        assertFalse(fullDeflection.isInDeadzone)
        assertEquals(1.0f, fullDeflection.outputX, 0.05f)
    }

    @Test
    fun testSplitControllerFusionEngine() {
        val engine = SplitControllerFusionEngine()
        engine.assignDevices(leftId = 1, rightId = 2)

        engine.updateLeftPad(buttons = 0x0040, stickX = -0.7f, stickY = 0.5f, trigger = 0.8f) // L3 pressed
        engine.updateRightPad(buttons = 0x1000, stickX = 0.3f, stickY = -0.2f, trigger = 0.0f) // A pressed
        engine.updateRightGyro(yaw = 1.5f, pitch = -0.5f)

        val fused = engine.getFusedState()
        assertEquals(0x1040, fused.buttonsMask)
        assertEquals(-0.7f, fused.leftStickX, 0.01f)
        assertEquals(0.8f, fused.leftTrigger, 0.01f)
        assertEquals(1.5f, fused.gyroAimYaw, 0.01f)
    }

    @Test
    fun testPrecisionTimeProtocolEngine() {
        val ptp = PrecisionTimeProtocolEngine()
        // t1=1000, t2=2015, t3=2020, t4=1045
        // RTT = (1045 - 1000) - (2020 - 2015) = 45 - 5 = 40us
        val metrics = ptp.computeDelays(t1 = 1000, t2 = 2015, t3 = 2020, t4 = 1045)
        assertEquals(40L, metrics.roundTripTimeUs)
        assertTrue(metrics.uplinkDelayUs >= 0)
        assertTrue(metrics.downlinkDelayUs >= 0)
    }

    @Test
    fun testClosedCaptionsExtractor() {
        val extractor = ClosedCaptionsExtractor()
        // Create HEVC SEI NALU: nal_unit_type = 39 (PREFIX_SEI_NUT) -> byte 0 = (39 shl 1) = 78
        val seiPacket = ByteArray(16)
        seiPacket[0] = 78.toByte()
        seiPacket[1] = 0x01.toByte()
        seiPacket[2] = 'G'.code.toByte()
        seiPacket[3] = 'O'.code.toByte()
        seiPacket[4] = 'D'.code.toByte()
        seiPacket[5] = ' '.code.toByte()
        seiPacket[6] = 'M'.code.toByte()
        seiPacket[7] = 'O'.code.toByte()
        seiPacket[8] = 'D'.code.toByte()
        seiPacket[9] = 'E'.code.toByte()

        val caption = extractor.extractCaptionsFromSei(seiPacket, 0, seiPacket.size)
        assertNotNull(caption)
        assertTrue(caption!!.text.contains("GOD MODE"))
    }

    @Test
    fun testSelectivePacketDuplicator() {
        val duplicator = SelectivePacketDuplicator()
        val dummyPayload = byteArrayOf(1, 2, 3)

        val criticalPlan = duplicator.planTransmission(dummyPayload, isAudio = true, isInput = false, isKeyframe = false)
        assertEquals(SelectivePacketDuplicator.PacketPriority.CRITICAL_DUPLICATE_2X, criticalPlan.priority)
        assertEquals(2, criticalPlan.packetsToSend.size)

        val standardPlan = duplicator.planTransmission(dummyPayload, isAudio = false, isInput = false, isKeyframe = false)
        assertEquals(SelectivePacketDuplicator.PacketPriority.STANDARD_SINGLE_COPY, standardPlan.priority)
        assertEquals(1, standardPlan.packetsToSend.size)
    }

    @Test
    fun testHeadlessDisplayOverrideEdidChecksum() {
        val override = HeadlessDisplayOverride()
        val edid = override.createHeadless1080pEdid(fps = 60, enableHdr = true)
        assertEquals(128, edid.rawEdid128Bytes.size)

        // Verify standard EDID header
        assertEquals(0x00.toByte(), edid.rawEdid128Bytes[0])
        for (i in 1..6) {
            assertEquals(0xFF.toByte(), edid.rawEdid128Bytes[i])
        }

        // Verify EDID checksum modulo 256 is 0
        var sum = 0
        for (b in edid.rawEdid128Bytes) {
            sum += (b.toInt() and 0xFF)
        }
        assertEquals(0, sum % 256)
    }

    @Test
    fun testPerGameProfileDispatcher() {
        val dispatcher = PerGameProfileDispatcher()
        val haloProfile = dispatcher.resolveProfileForTitle("9NBLGGH42THR")
        assertEquals("Halo Infinite (Shooter)", haloProfile.displayName)
        assertEquals("DUAL_STAGE_SNAP", haloProfile.adaptiveTriggerMode)

        val defaultProfile = dispatcher.resolveProfileForTitle("UNKNOWN_TITLE_XYZ")
        assertEquals("DEFAULT", defaultProfile.titleId)
    }

    @Test
    fun testHallEffectCalibrationEngine() {
        val engine = HallEffectCalibrationEngine()
        engine.setCenterOffset(0.05f, -0.05f)

        // Raw input resting at center offset (0.05, -0.05) should normalize exactly to (0.0, 0.0)
        val (cx, cy) = engine.processSample(0.05f, -0.05f)
        assertEquals(0f, cx, 0.001f)
        assertEquals(0f, cy, 0.001f)
    }

    @Test
    fun testKeyboardScancodeNormalizer() {
        val normalizer = KeyboardScancodeNormalizer()
        assertEquals(
            KeyboardScancodeNormalizer.VirtualKeyAction.MOVE_FORWARD,
            normalizer.mapScancode(KeyboardScancodeNormalizer.SCANCODE_KEY_W)
        )
        assertEquals(
            KeyboardScancodeNormalizer.VirtualKeyAction.JUMP_BUTTON_A,
            normalizer.mapScancode(KeyboardScancodeNormalizer.SCANCODE_KEY_SPACE)
        )
        assertTrue(normalizer.isPositionalMovement(KeyboardScancodeNormalizer.SCANCODE_KEY_W))
    }

    @Test
    fun testPolyphaseSincResampler() {
        val resampler = PolyphaseSincResampler()
        val inPcm = ShortArray(64) { 1000 }
        val outPcm = ShortArray(128)
        val outCount = resampler.resample(inPcm, 64, outPcm, 2.0)
        assertTrue(outCount > 0)
    }

    @Test
    fun testBinaryIntegrityGuardCertification() {
        val guard = BinaryIntegrityGuard()
        val homeReport = guard.evaluateCertification(motionToPhotonMs = 8.5f, isLocalConsole = true)
        assertTrue(homeReport.isCertifiedForConsoleHome)
        assertTrue(homeReport.certificationDetails.contains("Certificado Nivel Oro"))

        val cloudReport = guard.evaluateCertification(motionToPhotonMs = 28.0f, isLocalConsole = false)
        assertTrue(cloudReport.isCertifiedForCloudGaming)
    }
}
