package com.aistudio.xcloudgodmode.ai

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
import com.aistudio.xcloudgodmode.ai.services.GeminiService
import com.aistudio.xcloudgodmode.ai.services.NetworkClient
import com.aistudio.xcloudgodmode.ai.services.WebRTCStreamManager
import com.aistudio.xcloudgodmode.ai.services.XboxFullAuthManager
import com.aistudio.xcloudgodmode.ai.ui.screens.HomeScreen
import com.aistudio.xcloudgodmode.ai.ui.screens.StreamScreen
import com.aistudio.xcloudgodmode.ai.ui.theme.AppLocaleManager
import com.aistudio.xcloudgodmode.ai.ui.theme.LocalAppStrings
import com.aistudio.xcloudgodmode.ai.ui.theme.LocalScreenScaleProvider
import com.aistudio.xcloudgodmode.ai.viewmodels.HomeViewModel
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import com.aistudio.xcloudgodmode.ai.workers.TokenRefreshWorker
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var networkClient: NetworkClient
    private lateinit var xboxAuthManager: XboxFullAuthManager
    private lateinit var streamManager: WebRTCStreamManager
    private lateinit var geminiService: GeminiService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Network with DoH for bypass
        networkClient = NetworkClient(enableDoh = true)
        xboxAuthManager = XboxFullAuthManager(networkClient)
        streamManager = WebRTCStreamManager(this, networkClient)
        geminiService = GeminiService()

        // Initialize God Mode Native Sound Engine
        com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.init(this)
        
        // Schedule Silent Token Refresh Worker
        scheduleTokenRefreshWorker()
        
        handleIntent(intent)

        setContent {
            val homeViewModel: HomeViewModel = viewModel()
            val settings by homeViewModel.settings.collectAsState()
            val currentLanguage by AppLocaleManager.currentLanguageState.collectAsState()
            val currentStrings by AppLocaleManager.currentStrings.collectAsState()

            // Keep AppLocaleManager in sync with settings.appLanguage
            LaunchedEffect(settings.appLanguage) {
                AppLocaleManager.setLanguageByCode(settings.appLanguage)
            }

            XCloudGodModeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LocalScreenScaleProvider(settings = settings) {
                        CompositionLocalProvider(LocalAppStrings provides currentStrings) {
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

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService(Intent(this, com.aistudio.xcloudgodmode.ai.services.StreamingService::class.java))
    }
}

@Composable
fun MainNavigation(
    authManager: XboxFullAuthManager,
    streamManager: WebRTCStreamManager,
    geminiService: GeminiService,
    homeViewModel: HomeViewModel
) {
    val navController = rememberNavController()
    val settings by homeViewModel.settings.collectAsState()

    var activeGsToken by remember { mutableStateOf("demo_token") }
    var activeSessionPath by remember { mutableStateOf("https://uks.core.gssv-play-prod.xboxlive.com/v2/login/user") }
    var activeTitleId by remember { mutableStateOf("9NKX70BBCDRN") }
    var activeTitleName by remember { mutableStateOf("Forza Horizon 5") }

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                authManager = authManager,
                onLaunchGame = { titleId, path ->
                    activeGsToken = "demo_token"
                    activeSessionPath = path
                    activeTitleId = titleId
                    activeTitleName = "Xbox Cloud Title"
                    navController.navigate("stream")
                },
                onLaunchConsole = { consoleId ->
                    activeGsToken = "demo_console_token"
                    activeSessionPath = "https://ukw.core.gssv-play-prod.xboxlive.com/v2/login/user"
                    activeTitleId = consoleId
                    activeTitleName = "Xbox Console Remote Play"
                    navController.navigate("stream")
                },
                viewModel = homeViewModel
            )
        }
        composable("stream") {
            StreamScreen(
                streamManager = streamManager,
                gsToken = activeGsToken,
                sessionPath = activeSessionPath,
                settings = settings,
                titleId = activeTitleId,
                gameTitle = activeTitleName,
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
