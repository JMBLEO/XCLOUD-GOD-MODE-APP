package com.aistudio.xcloudgodmode.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aistudio.xcloudgodmode.ai.services.GeminiService
import com.aistudio.xcloudgodmode.ai.services.NetworkClient
import com.aistudio.xcloudgodmode.ai.services.WebRTCStreamManager
import com.aistudio.xcloudgodmode.ai.services.XboxFullAuthManager
import com.aistudio.xcloudgodmode.ai.ui.screens.OptimizationSettingsScreen
import com.aistudio.xcloudgodmode.ai.ui.screens.StreamScreen
import com.aistudio.xcloudgodmode.ai.ui.viewmodels.OptimizationViewModel

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
    
    var activeGsToken by remember { mutableStateOf("") }
    var activeSessionPath by remember { mutableStateOf("") }

    NavHost(navController = navController, startDestination = "catalog") {
        composable("catalog") {
            CatalogPlaceholder(
                onStartStream = { token, path ->
                    activeGsToken = token
                    activeSessionPath = path
                    navController.navigate("stream")
                },
                onOpenSettings = { navController.navigate("settings") }
            )
        }
        composable("stream") {
            StreamScreen(
                streamManager = streamManager,
                gsToken = activeGsToken,
                sessionPath = activeSessionPath,
                onClose = { navController.popBackStack() },
                onOpenSettings = { navController.navigate("settings") }
            )
        }
        composable("settings") {
            val factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return OptimizationViewModel(geminiService) as T
                }
            }
            val optimizationViewModel: OptimizationViewModel = viewModel(factory = factory)
            
            OptimizationSettingsScreen(
                viewModel = optimizationViewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

@Composable
fun CatalogPlaceholder(
    onStartStream: (String, String) -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Xbox Cloud Gaming - Native Client", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = { 
            onStartStream("demo_token", "https://uks.core.gssv-play-prod.xboxlive.com/v2/login/user")
        }) {
            Text("Launch Demo Stream (Forza Horizon 5)")
        }
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(onClick = onOpenSettings) {
            Text("Optimization Settings")
        }
    }
}

@Composable
fun XCloudGodModeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(),
        content = content
    )
}
