package com.aistudio.xcloudgodmode.ai

import android.os.Bundle
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
import com.aistudio.xcloudgodmode.ai.services.GeminiService
import com.aistudio.xcloudgodmode.ai.services.NetworkClient
import com.aistudio.xcloudgodmode.ai.services.WebRTCStreamManager
import com.aistudio.xcloudgodmode.ai.services.XboxFullAuthManager
import com.aistudio.xcloudgodmode.ai.ui.screens.HomeScreen
import com.aistudio.xcloudgodmode.ai.ui.screens.StreamScreen

class MainActivity : ComponentActivity() {

    private lateinit var networkClient: NetworkClient
    private lateinit var xboxAuthManager: XboxFullAuthManager
    private lateinit var streamManager: WebRTCStreamManager
    private lateinit var geminiService: GeminiService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        networkClient = NetworkClient()
        xboxAuthManager = XboxFullAuthManager(networkClient)
        streamManager = WebRTCStreamManager(this, networkClient)
        geminiService = GeminiService()

        // Initialize God Mode Native Sound Engine
        com.aistudio.xcloudgodmode.ai.audio.PlayStaxBoxNativeSoundEngine.init(this)

        setContent {
            XCloudGodModeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavigation(xboxAuthManager, streamManager, geminiService)
                }
            }
        }
    }
}

@Composable
fun MainNavigation(
    authManager: XboxFullAuthManager,
    streamManager: WebRTCStreamManager,
    geminiService: GeminiService
) {
    val navController = rememberNavController()

    val homeViewModel: com.aistudio.xcloudgodmode.ai.viewmodels.HomeViewModel = viewModel()
    val settings by homeViewModel.settings.collectAsState()

    var activeGsToken by remember { mutableStateOf("demo_token") }
    var activeSessionPath by remember { mutableStateOf("https://uks.core.gssv-play-prod.xboxlive.com/v2/login/user") }

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                authManager = authManager,
                onLaunchGame = { titleId, path ->
                    activeGsToken = "demo_token"
                    activeSessionPath = path
                    navController.navigate("stream")
                },
                onLaunchConsole = { consoleId ->
                    activeGsToken = "demo_console_token"
                    activeSessionPath = "https://ukw.core.gssv-play-prod.xboxlive.com/v2/login/user"
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
