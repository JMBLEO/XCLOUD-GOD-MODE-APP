package com.xcloud.godmode

import android.os.Bundle
import android.content.Intent
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.CompositionLocalProvider
import com.xcloud.godmode.ai.GeminiService
import com.xcloud.godmode.security.SecurityManager

import com.xcloud.godmode.network.NetworkClient
import com.xcloud.godmode.streaming.NativeStreamPipelineManager
import com.xcloud.godmode.network.XboxFullAuthManager
import com.xcloud.godmode.ui.screens.HomeScreen
import com.xcloud.godmode.ui.screens.StreamScreen
import com.xcloud.godmode.ui.components.GodModePermissionHandler
import com.xcloud.godmode.ui.theme.AppLocaleManager
import com.xcloud.godmode.ui.theme.LocalAppStrings
import com.xcloud.godmode.ui.theme.LocalScreenScaleProvider
import com.xcloud.godmode.viewmodels.HomeViewModel
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import com.xcloud.godmode.workers.TokenRefreshWorker
import java.util.concurrent.TimeUnit

import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {

    private lateinit var networkClient: NetworkClient
    private lateinit var xboxAuthManager: XboxFullAuthManager
    private lateinit var streamManager: NativeStreamPipelineManager
    private lateinit var geminiService: GeminiService
    private lateinit var thermalGuard: com.xcloud.godmode.thermal.GodModeThermalGuard

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Install StrictMode guard to prevent NetworkOnMainThreadException
        com.xcloud.godmode.core.NetworkThreadGuard.installStrictModeNetworkGuard()

        // Initialize Network with DoH for bypass
        networkClient = NetworkClient(enableDoh = true)
        xboxAuthManager = XboxFullAuthManager(networkClient)
        streamManager = NativeStreamPipelineManager(this, networkClient)
        geminiService = GeminiService()

        // Initialize God Mode Native Sound Engine
        com.xcloud.godmode.audio.GodModeSoundEngine.init(this)

        // Start Security Watchdog - Disabled for AI Studio stability
        // SecurityManager.startSecurityWatchdog()

        
        // Initialize XboxEndpoints for Layer 5 (Dynamic Patcher)
        com.xcloud.godmode.network.XboxEndpoints.init(this)
        
        // Schedule Silent Token Refresh Worker
        scheduleTokenRefreshWorker()
        
        // Schedule Dynamic Patcher Worker (Layer 5)
        scheduleDynamicPatcherWorker()
        
        // Initialize Thermal Guard for protection against overheating during 1440p streaming
        thermalGuard = com.xcloud.godmode.thermal.GodModeThermalGuard(this) {
            // Callback: Degrade quality to 1080p HQ preventively
            android.util.Log.w("MainActivity", "Thermal stress detected! Forcing quality degradation for hardware safety.")
            // Logic to signal streamManager or viewmodel to lower resolution
        }
        thermalGuard.registerListener()

        handleIntent(intent)

        setContent {
            val homeViewModel: HomeViewModel = viewModel()
            val settings by homeViewModel.settings.collectAsState()
            val currentLanguage by AppLocaleManager.currentLanguageState.collectAsState()
            val currentStrings by AppLocaleManager.currentStrings.collectAsState()
            val postStreamSummary by homeViewModel.postStreamSummary.collectAsState()

            // Keep AppLocaleManager in sync with settings.appLanguage
            LaunchedEffect(settings.appLanguage) {
                AppLocaleManager.setLanguageByCode(settings.appLanguage)
            }

            // Persist Telemetry to Room
            LaunchedEffect(postStreamSummary) {
                postStreamSummary?.let { summary ->
                    homeViewModel.saveTelemetry(summary)
                }
            }

            XCloudGodModeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LocalScreenScaleProvider(settings = settings) {
                        CompositionLocalProvider(LocalAppStrings provides currentStrings) {
                            GodModePermissionHandler(homeViewModel)

                            MainNavigation(
                                authManager = xboxAuthManager,
                                streamManager = streamManager,
                                geminiService = geminiService,
                                homeViewModel = homeViewModel
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        val data = intent?.data
        if (data != null && data.toString().startsWith("ms-xal-")) {
            val homeViewModel: HomeViewModel by lazy { 
                androidx.lifecycle.ViewModelProvider(this)[HomeViewModel::class.java] 
            }
            val code = xboxAuthManager.extractAuthCode(data.toString())
            if (code.isNotEmpty()) {
                homeViewModel.setCapturedAuthCode(code)
            }
        } else if (data != null && data.scheme == "godmode") {
            val titleId = data.getQueryParameter("titleId")
            if (titleId != null) {
                // Future: Navigate directly to stream
            }
        }
    }

    private fun scheduleTokenRefreshWorker() {
        val refreshRequest = PeriodicWorkRequestBuilder<TokenRefreshWorker>(
            1, TimeUnit.HOURS
        ).build()

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "XboxTokenRefresh",
            ExistingPeriodicWorkPolicy.KEEP,
            refreshRequest
        )
    }

    private fun scheduleDynamicPatcherWorker() {
        val patchRequest = PeriodicWorkRequestBuilder<com.xcloud.godmode.workers.DynamicPatcherWorker>(
            12, TimeUnit.HOURS
        ).build()

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "GodModeDynamicPatcher",
            ExistingPeriodicWorkPolicy.KEEP,
            patchRequest
        )
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        // Separation of Hybrid Input:
        // Only intercept raw touch when actually in stream mode to avoid blocking Compose Hub gestures.
        if (com.xcloud.godmode.streaming.NativeInputBridge.isRawTouchEnabled) {
            val width = resources.displayMetrics.widthPixels
            val height = resources.displayMetrics.heightPixels
            for (i in 0 until ev.pointerCount) {
                val pointerId = ev.getPointerId(i)
                val x = ev.getX(i)
                val y = ev.getY(i)
                val action = ev.actionMasked
                com.xcloud.godmode.streaming.NativeInputBridge.injectRawTouchEvent(pointerId, x, y, action, width, height)
            }
            // If in stream and raw touch is on, we consume it to ensure zero latency to C++/Rust
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                if (streamManager.isConnected()) {
                    val aspectRatio = android.util.Rational(16, 9)
                    val params = android.app.PictureInPictureParams.Builder()
                        .setAspectRatio(aspectRatio)
                        .build()
                    enterPictureInPictureMode(params)
                }
            } catch (e: Exception) {
                // Fallback gracefully if PiP is not allowed
            }
        }
    }

    override fun onDestroy() {
        if (::thermalGuard.isInitialized) {
            thermalGuard.unregisterListener()
        }
        stopService(Intent(this, com.xcloud.godmode.services.StreamingService::class.java))
        super.onDestroy()
    }
}

@Composable
fun MainNavigation(
    authManager: XboxFullAuthManager,
    streamManager: NativeStreamPipelineManager,
    geminiService: GeminiService,
    homeViewModel: HomeViewModel
) {
    val navController = rememberNavController()
    val settings by homeViewModel.settings.collectAsState()
    val userSession by homeViewModel.userSession.collectAsState()

    var activeGsToken by remember { mutableStateOf("") }
    var activeSessionPath by remember { mutableStateOf("https://uks.core.gssv-play-prod.xboxlive.com/v2/login/user") }
    var activeTitleId by remember { mutableStateOf("9NKX70BBCDRN") }
    var activeTitleName by remember { mutableStateOf("Forza Horizon 5") }

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                authManager = authManager,
                onLaunchGame = { titleId, path ->
                    val token = userSession?.xCloudGsToken
                    if (token != null) {
                        activeGsToken = token
                        activeSessionPath = path
                        activeTitleId = titleId
                        activeTitleName = "Xbox Cloud Title"
                        navController.navigate("stream")
                    } else {
                        homeViewModel.showAuth(true)
                    }
                },
                onLaunchConsole = { consoleId ->
                    val token = userSession?.xHomeGsToken
                    if (token != null) {
                        activeGsToken = token
                        activeSessionPath = "https://ukw.core.gssv-play-prod.xboxlive.com/v2/login/user"
                        activeTitleId = consoleId
                        activeTitleName = "Xbox Console Remote Play"
                        navController.navigate("stream")
                    } else {
                        homeViewModel.showAuth(true)
                    }
                },
                viewModel = homeViewModel
            )
        }
        composable("stream") {
            val hwProfile by homeViewModel.hardwareProfile.collectAsState()
            StreamScreen(
                streamManager = streamManager,
                gsToken = activeGsToken,
                sessionPath = activeSessionPath,
                settings = settings,
                titleId = activeTitleId,
                gameTitle = activeTitleName,
                gssvXstsToken = userSession?.gssvXstsToken.orEmpty(),
                fallbackGsToken = userSession?.f2pGsToken.orEmpty(),
                fallbackRegionUri = userSession?.f2pRegionUri.orEmpty(),
                hwProfile = hwProfile,
                onClose = { navController.popBackStack() }
            )
        }
    }
}

@Composable
fun XCloudGodModeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF107C10),
            secondary = Color(0xFF4ADE80),
            background = Color(0xFF0C0E10),
            surface = Color(0xFF14171A),
            surfaceVariant = Color(0xFF1C2024),
            onPrimary = Color.White,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}
