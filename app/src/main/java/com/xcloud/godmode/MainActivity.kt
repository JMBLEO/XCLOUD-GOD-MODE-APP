package com.xcloud.godmode

import android.os.Bundle
import android.content.Intent
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.WindowManager
import android.os.Build
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.CompositionLocalProvider
import com.xcloud.godmode.ai.GeminiService
import com.xcloud.godmode.services.SecurityManager

import com.xcloud.godmode.network.NetworkClient
import com.xcloud.godmode.streaming.NativeStreamPipelineManager
import com.xcloud.godmode.network.XboxFullAuthManager
import com.xcloud.godmode.ui.screens.HomeScreen
import com.xcloud.godmode.ui.screens.StreamScreen
import com.xcloud.godmode.ui.StreamPhase
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color

import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private lateinit var networkClient: NetworkClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Evitar que la ventana reserve espacio para las barras del sistema
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 2. Extender el contenido bajo el notch o recorte de cámara
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // 3. Mantener la pantalla encendida durante sesiones de juego
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyPersistentImmersiveMode()

        // Install StrictMode guard to prevent NetworkOnMainThreadException
        com.xcloud.godmode.network.NetworkThreadGuard.installStrictModeNetworkGuard()

        // Network client is lightweight, but we still use it sparingly
        networkClient = NetworkClient(enableDoh = true)
        
        handleIntent(intent)

        // Forzar renderizado inmersivo desde la raíz para prevenir pantallas negras por recortes de display
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        setContent {
            val rootContainer = com.xcloud.godmode.engine.GodModeRootContainer.getInstance(application)
            val homeViewModel: HomeViewModel = viewModel()
            val bootViewModel: com.xcloud.godmode.viewmodels.BootstrapperViewModel = viewModel()
            val authModalViewModel: com.xcloud.godmode.viewmodels.XboxAuthModalViewModel = viewModel(
                factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        return com.xcloud.godmode.viewmodels.XboxAuthModalViewModel(
                            rootContainer.msaAuthEngine,
                            rootContainer.msaDeviceCodeEngine
                        ) as T
                    }
                }
            )
            
            val bootState by bootViewModel.bootState.collectAsState()
            val settings by homeViewModel.settings.collectAsState()
            val currentStrings by AppLocaleManager.currentStrings.collectAsState()
            val postStreamSummary by homeViewModel.postStreamSummary.collectAsState()

            LaunchedEffect(settings.appLanguage) {
                AppLocaleManager.setLanguageByCode(settings.appLanguage)
            }

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
                    when (val state = bootState) {
                        is com.xcloud.godmode.viewmodels.BootState.Loading -> {
                            RootBootstrapperScreen(message = "Iniciando Motor God Mode v10...")
                        }
                        is com.xcloud.godmode.viewmodels.BootState.Error -> {
                            RootBootstrapperScreen(message = state.message, isError = true)
                        }
                        else -> {
                            LocalScreenScaleProvider(settings = settings) {
                                CompositionLocalProvider(LocalAppStrings provides currentStrings) {
                                    GodModePermissionHandler(homeViewModel)

                                    MainNavigation(
                                        authManager = rootContainer.authVault.let { com.xcloud.godmode.network.XboxFullAuthManager(rootContainer.networkClient) },
                                        streamManager = rootContainer.streamManager,
                                        geminiService = GeminiService(),
                                        homeViewModel = homeViewModel,
                                        authModalViewModel = authModalViewModel
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun RootBootstrapperScreen(message: String, isError: Boolean = false) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.fillMaxSize().background(Color(0xFF0C0E10)),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            if (!isError) {
                androidx.compose.material3.CircularProgressIndicator(color = Color(0xFF107C10))
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(24.dp))
            } else {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Default.ErrorOutline,
                    null,
                    tint = Color.Red,
                    modifier = Modifier.size(48.dp)
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))
            }
            androidx.compose.material3.Text(
                text = message,
                color = if (isError) Color.Red else Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        val data = intent?.data
        if (data != null && data.toString().startsWith("ms-xal-")) {
            val code = data.getQueryParameter("code")
            if (code != null) {
                val rootContainer = com.xcloud.godmode.engine.GodModeRootContainer.getInstance(application)
                // We need to find the ViewModel. In a real app we might use a shared state or singleton.
                // For this environment, we'll try to use a static reference or the rootContainer.
                android.util.Log.i("MainActivity", "Captured Xbox Auth Code: $code")
                
                // We'll use a hack to pass it to the active ViewModel if it's currently observing
                // but better is to have a dedicated flow in rootContainer.
                // For now, let's assume the ViewModel will check this state or we can use a SharedFlow.
                GlobalAuthCapture.emit(code)
            }
        } else if (data != null && data.scheme == "xcloudgodmode") {
            android.util.Log.i("MainActivity", "Loopback auth completed: $data")
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
                val rootContainer = com.xcloud.godmode.engine.GodModeRootContainer.getInstance(application)
                if (rootContainer.streamManager.isConnected()) {
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

    fun applyPersistentImmersiveMode() {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onResume() {
        super.onResume()
        window.decorView.post { applyPersistentImmersiveMode() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.postDelayed({ applyPersistentImmersiveMode() }, 100L)
        }
    }

    override fun onDestroy() {
        stopService(Intent(this, com.xcloud.godmode.services.StreamingService::class.java))
        super.onDestroy()
    }

    fun requestBiometricAuth(onSuccess: () -> Unit, onError: (String) -> Unit) {
        val authHelper = com.xcloud.godmode.services.BiometricAuthHelper(this)
        authHelper.promptAuth(
            onAuthenticated = onSuccess,
            onError = onError
        )
    }

    companion object {
        fun restoreImmersiveMode(activity: android.app.Activity?) {
            activity?.window?.let { win ->
                WindowCompat.setDecorFitsSystemWindows(win, false)
                val controller = WindowCompat.getInsetsController(win, win.decorView)
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

@Composable
fun MainNavigation(
    authManager: XboxFullAuthManager,
    streamManager: NativeStreamPipelineManager,
    geminiService: GeminiService,
    homeViewModel: HomeViewModel,
    authModalViewModel: com.xcloud.godmode.viewmodels.XboxAuthModalViewModel
) {
    val navController = rememberNavController()
    val uiState by homeViewModel.launchState.collectAsState()

    // Reactive Navigation: Handle transitions based on StreamPhase
    LaunchedEffect(uiState.phase) {
        when (uiState.phase) {
            StreamPhase.PREPARING -> {
                if (navController.currentDestination?.route != "stream") {
                    navController.navigate("stream")
                }
            }
            StreamPhase.IDLE -> {
                if (navController.currentDestination?.route == "stream") {
                    navController.popBackStack("home", inclusive = false)
                }
            }
            else -> {}
        }
    }

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                authManager = authManager,
                onLaunchGame = { titleId, _ ->
                    homeViewModel.launchGame(titleId)
                    // Navigation handled by LaunchedEffect
                },
                onLaunchConsole = { consoleId ->
                    // Simplified: just show auth for now if no session
                    homeViewModel.showAuth(true)
                },
                viewModel = homeViewModel,
                authModalViewModel = authModalViewModel
            )
        }
        composable("stream") {
            StreamScreen(
                viewModel = homeViewModel,
                onClose = { 
                    homeViewModel.stopStream()
                    // popBackStack handled by LaunchedEffect
                },
                onAction = { action ->
                    homeViewModel.handleStreamAction(action) {
                        // popBackStack handled by LaunchedEffect
                    }
                }
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
