package ua.chudo.scanner

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ua.chudo.scanner.ui.home.HomeScreen
import ua.chudo.scanner.ui.product.ProductScreen
import ua.chudo.scanner.ui.results.ResultsScreen
import ua.chudo.scanner.ui.results.PHOTO_QUERY
import ua.chudo.scanner.ui.scan.ScanMode
import ua.chudo.scanner.ui.scan.ScannerScreen
import ua.chudo.scanner.ui.theme.ChudoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChudoTheme {
                Surface(color = MaterialTheme.colorScheme.background) { AppNav() }
            }
        }
    }
}

private object Routes {
    const val HOME = "home"
    const val SCAN = "scan?mode={mode}"
    const val RESULTS = "results/{q}"
    const val PRODUCT = "product/{urlKey}?bc={bc}"

    fun scan(mode: ScanMode = ScanMode.BARCODE) = "scan?mode=${mode.name}"
    fun results(q: String) = "results/${Uri.encode(q)}"
    fun product(urlKey: String, bc: String?) = "product/${Uri.encode(urlKey)}" + (bc?.let { "?bc=$it" } ?: "")
}

@androidx.compose.runtime.Composable
private fun AppNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onScan = { nav.navigate(Routes.scan()) },
                onPhoto = { nav.navigate(Routes.scan(ScanMode.PHOTO)) },
                onSearch = { nav.navigate(Routes.results(it)) },
                onOpenProduct = { nav.navigate(Routes.product(it, null)) },
            )
        }
        composable(
            Routes.SCAN,
            arguments = listOf(navArgument("mode") { type = NavType.StringType; defaultValue = ScanMode.BARCODE.name }),
        ) { entry ->
            val app = LocalContext.current.chudoApp
            val mode = runCatching { ScanMode.valueOf(entry.arguments?.getString("mode").orEmpty()) }
                .getOrDefault(ScanMode.BARCODE)
            ScannerScreen(
                startMode = mode,
                onBarcode = { code ->
                    nav.navigate(Routes.results(code)) { popUpTo(Routes.SCAN) { inclusive = true } }
                },
                onPhoto = { jpeg ->
                    app.pendingPhoto = jpeg
                    nav.navigate(Routes.results(PHOTO_QUERY)) { popUpTo(Routes.SCAN) { inclusive = true } }
                },
                onClose = { nav.popBackStack() },
            )
        }
        composable(Routes.RESULTS, arguments = listOf(navArgument("q") { type = NavType.StringType })) {
            ResultsScreen(
                onBack = { nav.popBackStack() },
                onOpenProduct = { urlKey, bc, replace ->
                    nav.navigate(Routes.product(urlKey, bc)) {
                        if (replace) popUpTo(Routes.RESULTS) { inclusive = true }
                    }
                },
                onScanAgain = {
                    nav.navigate(Routes.scan()) { popUpTo(Routes.HOME) }
                },
                onPhotoAgain = {
                    nav.navigate(Routes.scan(ScanMode.PHOTO)) { popUpTo(Routes.HOME) }
                },
            )
        }
        composable(
            Routes.PRODUCT,
            arguments = listOf(
                navArgument("urlKey") { type = NavType.StringType },
                navArgument("bc") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) {
            ProductScreen(onBack = { nav.popBackStack() })
        }
    }
}
