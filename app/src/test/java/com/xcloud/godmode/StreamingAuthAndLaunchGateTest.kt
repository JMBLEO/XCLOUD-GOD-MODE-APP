package com.xcloud.godmode

import com.xcloud.godmode.network.CredentialResolutionResult
import com.xcloud.godmode.network.SessionVaultStorage
import com.xcloud.godmode.network.StreamingCredentialOrchestrator
import com.xcloud.godmode.network.StreamingSessionCredentials
import com.xcloud.godmode.services.EncryptedVaultService
import com.xcloud.godmode.viewmodels.EngineDialogState
import com.xcloud.godmode.viewmodels.StreamLaunchViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingAuthAndLaunchGateTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()

    private class MockContext(val folder: File) : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): android.content.Context = this
        override fun getNoBackupFilesDir(): File = folder
        override fun getFilesDir(): File = folder
        override fun getPackageName(): String = "com.xcloud.godmode"
    }

    private class MemoryVaultStorage(
        var credentials: StreamingSessionCredentials? = null
    ) : SessionVaultStorage {
        override suspend fun loadCredentials(): StreamingSessionCredentials? = credentials
        override suspend fun saveCredentials(credentials: StreamingSessionCredentials) {
            this.credentials = credentials
        }
        override suspend fun clearCredentials() {
            credentials = null
        }
    }

    private lateinit var mockContext: MockContext
    private lateinit var vaultService: EncryptedVaultService

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        EncryptedVaultService.resetInstanceForTesting()
        mockContext = MockContext(tempFolder.newFolder("nobackup"))
        vaultService = EncryptedVaultService.createForTesting(mockContext)
    }

    @After
    fun tearDown() {
        EncryptedVaultService.resetInstanceForTesting()
        Dispatchers.resetMain()
    }

    @Test
    fun testEncryptedVaultRoundTrip() {
        val testData = "Hello God Mode Secure Session"
        vaultService.encryptAndSave(testData)

        val decrypted = vaultService.decryptAndRead()
        assertEquals(testData, decrypted)
    }

    @Test
    fun testEncryptedVaultTamperingPurgesFile() {
        val testData = "Important Credentials"
        vaultService.encryptAndSave(testData)

        // Corrupt the file
        val file = File(mockContext.noBackupFilesDir, "secure_session_vault.dat")
        assertTrue(file.exists())
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        file.writeBytes(bytes)

        // Decrypting tampered file must fail and purge file
        val decrypted = vaultService.decryptAndRead()
        assertNull(decrypted)
        assertFalse(file.exists())
    }

    @Test
    fun testEncryptedVaultCredentialsSerialization() = runTest {
        val creds = StreamingSessionCredentials(
            gamertag = "JMB Nel",
            xuid = "2533274819283",
            uhs = "uhs_12345",
            msaRefreshToken = "rt_sample_token",
            xCloudGsToken = "gstoken_sample",
            xCloudBaseUri = "https://uks.core.gssv-play-prod.xboxlive.com/v2/login/user",
            expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L
        )

        vaultService.saveCredentials(creds)
        val loaded = vaultService.loadCredentials()

        assertNotNull(loaded)
        assertEquals("JMB Nel", loaded!!.gamertag)
        assertEquals("rt_sample_token", loaded.msaRefreshToken)
        assertEquals("gstoken_sample", loaded.xCloudGsToken)
        assertTrue(loaded.isStreamingTokenValid())

        vaultService.clearCredentials()
        assertNull(vaultService.loadCredentials())
    }

    @Test
    fun testEncryptedVaultTokenBundleSerialization() = runTest {
        val bundle = com.xcloud.godmode.network.XCloudTokenBundle(
            gamertag = "JMB Nel",
            xuid = "2533274819283",
            uhs = "uhs_9999",
            msaAccessToken = "access_token_val",
            msaRefreshToken = "refresh_token_val",
            webXstsToken = "web_xsts_val",
            gssvXstsToken = "gssv_xsts_val",
            xgpuGsToken = "xgpu_gs_val",
            f2pGsToken = "f2p_gs_val",
            defaultRegionBaseUri = "https://uks.core.gssv-play-prod.xboxlive.com/v2/login/user",
            regionsJson = "[]",
            gsTokenExpiresAtEpochMs = System.currentTimeMillis() + 7_200_000L
        )

        vaultService.saveTokenBundle(bundle)
        val loaded = vaultService.loadTokenBundle()

        assertNotNull(loaded)
        assertEquals("JMB Nel", loaded!!.gamertag)
        assertEquals("refresh_token_val", loaded.msaRefreshToken)
        assertEquals("xgpu_gs_val", loaded.xgpuGsToken)
        assertTrue(loaded.isGsTokenValid())

        val creds = vaultService.loadCredentials()
        assertNotNull(creds)
        assertEquals("JMB Nel", creds!!.gamertag)
        assertEquals("refresh_token_val", creds.msaRefreshToken)
        assertEquals("xgpu_gs_val", creds.xCloudGsToken)
    }

    @Test
    fun testStreamingTokenExpirationMath() {
        val now = System.currentTimeMillis()

        val validCreds = StreamingSessionCredentials(
            gamertag = "JMB Nel",
            xuid = "xid",
            uhs = "uhs",
            msaRefreshToken = "rt",
            xCloudGsToken = "gsToken",
            xCloudBaseUri = "https://gssv.example.com",
            expiresAtEpochMs = now + 600_000L // 10 minutes remaining (> 5m threshold)
        )
        assertTrue(validCreds.isStreamingTokenValid())

        val expiredCreds = validCreds.copy(
            expiresAtEpochMs = now + 120_000L // Only 2 minutes remaining (< 5m threshold)
        )
        assertFalse(expiredCreds.isStreamingTokenValid())

        val missingTokenCreds = validCreds.copy(
            xCloudGsToken = ""
        )
        assertFalse(missingTokenCreds.isStreamingTokenValid())
    }

    @Test
    fun testOrchestratorReturnsReadyWhenTokenValid() = runTest {
        val creds = StreamingSessionCredentials(
            gamertag = "JMB Nel",
            xuid = "xid",
            uhs = "uhs",
            msaRefreshToken = "rt",
            xCloudGsToken = "valid_gstoken",
            xCloudBaseUri = "https://gssv.example.com",
            expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L
        )
        val storage = MemoryVaultStorage(creds)
        val orchestrator = StreamingCredentialOrchestrator(storage)

        val result = orchestrator.ensureValidStreamingToken()
        assertTrue(result is CredentialResolutionResult.Ready)
        assertEquals("JMB Nel", (result as CredentialResolutionResult.Ready).credentials.gamertag)
    }

    @Test
    fun testOrchestratorRequiresInteractiveLoginWhenEmpty() = runTest {
        val storage = MemoryVaultStorage(null)
        val orchestrator = StreamingCredentialOrchestrator(storage)

        val result = orchestrator.ensureValidStreamingToken()
        assertTrue(result is CredentialResolutionResult.RequiresInteractiveLogin)
    }

    @Test
    fun testOrchestratorRequiresLoginWhenRefreshTokenBlank() = runTest {
        val zombieCreds = StreamingSessionCredentials(
            gamertag = "JMB Nel",
            xuid = "xid",
            uhs = "uhs",
            msaRefreshToken = "", // No refresh token!
            xCloudGsToken = "",   // Cache cleared!
            xCloudBaseUri = "",
            expiresAtEpochMs = 0L
        )
        val storage = MemoryVaultStorage(zombieCreds)
        val orchestrator = StreamingCredentialOrchestrator(storage)

        val result = orchestrator.ensureValidStreamingToken()
        assertTrue(result is CredentialResolutionResult.RequiresInteractiveLogin)
        assertNull(storage.credentials) // Zombie session must be cleared!
    }

    @Test
    fun testStreamLaunchViewModelDismissDialog() = runTest {
        val storage = MemoryVaultStorage(null)
        val orchestrator = StreamingCredentialOrchestrator(storage)
        val viewModel = StreamLaunchViewModel(orchestrator, storage)
        testScheduler.advanceUntilIdle()

        viewModel.dismissEngineDialog(openLoginIfAuthRequired = false)
        assertEquals(EngineDialogState.Hidden, viewModel.uiState.value.engineDialog)
    }

    @Test
    fun testStreamLaunchViewModelDirectDeviceAuthOnRequiresLogin() = runTest {
        val storage = MemoryVaultStorage(null)
        val orchestrator = StreamingCredentialOrchestrator(storage)
        val viewModel = StreamLaunchViewModel(orchestrator, storage)
        testScheduler.advanceUntilIdle()

        var streamStarted = false
        viewModel.onPlayNowClicked("9NKX70BBCDRN") {
            streamStarted = true
        }
        testScheduler.advanceUntilIdle()

        assertFalse(streamStarted)
        assertFalse(viewModel.uiState.value.isLoggedIn)
        // Zero-friction requirement: must immediately show DeviceAuthModal
        assertTrue(viewModel.uiState.value.showDeviceAuthModal)
    }

    @Test
    fun testStreamLaunchViewModelChipInteractions() = runTest {
        val storage = MemoryVaultStorage(null)
        val orchestrator = StreamingCredentialOrchestrator(storage)
        val viewModel = StreamLaunchViewModel(orchestrator, storage)
        testScheduler.advanceUntilIdle()

        // Logged out: clicking chip opens device auth modal
        viewModel.onUserChipClicked()
        assertTrue(viewModel.uiState.value.showDeviceAuthModal)
        assertFalse(viewModel.uiState.value.showProfileMenu)

        viewModel.dismissDeviceAuthModal()
        assertFalse(viewModel.uiState.value.showDeviceAuthModal)

        // When auth completes
        val newCreds = StreamingSessionCredentials(
            gamertag = "JMB Nel",
            xuid = "123",
            uhs = "uhs",
            msaRefreshToken = "rt",
            xCloudGsToken = "gs",
            xCloudBaseUri = "https://uks.gssv.example.com",
            expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L
        )
        viewModel.onDeviceAuthCompleted(newCreds)
        assertTrue(viewModel.uiState.value.isLoggedIn)
        assertEquals("JMB Nel", viewModel.uiState.value.gamertag)

        // Logged in: clicking chip opens profile menu
        viewModel.onUserChipClicked()
        assertTrue(viewModel.uiState.value.showProfileMenu)

        // Force re-login: clears vault and opens device auth modal
        viewModel.onForceReLoginRequested()
        assertFalse(viewModel.uiState.value.isLoggedIn)
        assertNull(viewModel.uiState.value.gamertag)
        assertTrue(viewModel.uiState.value.showDeviceAuthModal)
        assertNull(storage.credentials)
    }
}
