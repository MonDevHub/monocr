package dev.janakhpon.monocr

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.janakhpon.monocr.data.HistoryDatabase
import dev.janakhpon.monocr.engine.OcrRepository
import dev.janakhpon.monocr.ui.ContributeViewModel
import dev.janakhpon.monocr.ui.FeedbackViewModel
import dev.janakhpon.monocr.ui.MainViewModel
import dev.janakhpon.monocr.ui.screens.AboutScreen
import dev.janakhpon.monocr.ui.screens.ContributeScreen
import dev.janakhpon.monocr.ui.screens.DocsScreen
import dev.janakhpon.monocr.ui.screens.FeedbackScreen
import dev.janakhpon.monocr.ui.screens.HomeScreen
import dev.janakhpon.monocr.ui.screens.PrivacyScreen
import dev.janakhpon.monocr.ui.theme.MonOCRTheme

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        dev.janakhpon.monocr.util.MonLogger.applicationContext = applicationContext

        // Boot background Cloudflare R2 Sync
        dev.janakhpon.monocr.engine.SyncService.getInstance(applicationContext).start()

        setContent {
            MonOCRTheme {
                val navController = rememberNavController()

                // Shared repository — created once, injected into each ViewModel
                val repository = remember {
                    OcrRepository(applicationContext)
                }

                fun <T : ViewModel> repositoryFactory(create: (OcrRepository) -> T) =
                    object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <VM : ViewModel> create(modelClass: Class<VM>): VM =
                            create(repository) as VM
                    }

                val vm: MainViewModel = viewModel(factory = repositoryFactory { MainViewModel(it) })
                val contributeVm: ContributeViewModel = viewModel(factory = repositoryFactory { ContributeViewModel(it) })
                val feedbackVm: FeedbackViewModel = viewModel(factory = repositoryFactory { FeedbackViewModel(it) })

                val uiState by vm.uiState.collectAsState()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "home",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        composable("home") {
                            HomeScreen(
                                uiState                = uiState,
                                viewModel              = vm,
                                onNavigateToAbout      = { navController.navigate("about") },
                                onNavigateToDocs       = { navController.navigate("docs") },
                                onNavigateToContribute = { text ->
                                    val route = if (text.isNotBlank()) "contribute?text=${Uri.encode(text)}" else "contribute"
                                    navController.navigate(route)
                                },
                                onNavigateToFeedback   = { text, uri ->
                                    var route = "feedback?text=${Uri.encode(text)}"
                                    uri?.let { route += "&imageUri=${Uri.encode(it.toString())}" }
                                    navController.navigate(route)
                                },
                                onNavigateToPrivacy    = { navController.navigate("privacy") }
                            )
                        }
                        composable("about") {
                            AboutScreen(
                                onBack = { navController.popBackStack() },
                                onNavigateToDocs = { navController.navigate("docs") },
                                onNavigateToContribute = { navController.navigate("contribute") },
                                onNavigateToFeedback = { navController.navigate("feedback") },
                                onNavigateToPrivacy = { navController.navigate("privacy") }
                            )
                        }
                        composable("privacy") {
                            PrivacyScreen(onNavigateBack = { navController.popBackStack() })
                        }
                        composable(
                            route = "feedback?text={text}&imageUri={imageUri}",
                            arguments = listOf(
                                androidx.navigation.navArgument("text") { defaultValue = "" },
                                androidx.navigation.navArgument("imageUri") { defaultValue = "" }
                            )
                        ) { backStackEntry ->
                            val text = backStackEntry.arguments?.getString("text") ?: ""
                            val imageUriStr = backStackEntry.arguments?.getString("imageUri") ?: ""
                            val imageUri = if (imageUriStr.isNotEmpty()) Uri.parse(imageUriStr) else null
                            FeedbackScreen(
                                viewModel = feedbackVm,
                                originalText = text,
                                sourceUriDefault = imageUri,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable("contribute?text={text}") { backStackEntry ->
                            val text = backStackEntry.arguments?.getString("text") ?: ""
                            ContributeScreen(
                                viewModel = contributeVm,
                                initialText = text,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable("docs") {
                            DocsScreen(onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}

