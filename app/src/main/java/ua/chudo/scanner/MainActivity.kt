package ua.chudo.scanner

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ua.chudo.scanner.ui.common.RegionPickerSheet
import ua.chudo.scanner.ui.common.openUrl
import uniffi.chudo_core.Lang
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

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val ctx = LocalContext.current
    val prefs = ctx.chudoApp.prefs
    val region by prefs.region.collectAsStateWithLifecycle()
    val lang by prefs.lang.collectAsStateWithLifecycle()
    var showRegions by rememberSaveable { mutableStateOf(false) }
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val showBar = route != null && route != Routes.SCAN

    Scaffold(
        // Отступы системных панелей обрабатывают сами экраны; здесь — только высота нижней панели.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBar) BottomTabs(
                onSearch = {
                    if (route != Routes.HOME) nav.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onScan = { nav.navigate(Routes.scan()) },
                onCity = { showRegions = true },
                onSite = { ctx.openUrl(if (lang == Lang.RU) "$SITE/ru/" else "$SITE/ua/") },
            )
        },
    ) { inner ->
        AppNavHost(nav, Modifier.padding(bottom = inner.calculateBottomPadding()).consumeWindowInsets(inner))
    }

    if (showRegions) {
        RegionPickerSheet(
            regions = prefs.allRegions,
            selected = region.code,
            onPick = { prefs.setRegion(it.code); showRegions = false },
            onDismiss = { showRegions = false },
        )
    }
}

private const val SITE = "https://chudo-ostriv.com.ua"

/** Нижняя панель как в веб-версии: Пошук · Сканер · Місто · Сайт. */
@Composable
private fun BottomTabs(onSearch: () -> Unit, onScan: () -> Unit, onCity: () -> Unit, onSite: () -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = true, onClick = onSearch,
            icon = { Icon(Icons.Rounded.Search, null) }, label = { Text("Пошук") },
        )
        NavigationBarItem(
            selected = false, onClick = onScan,
            icon = { Icon(Icons.Rounded.QrCodeScanner, null) }, label = { Text("Сканер") },
        )
        NavigationBarItem(
            selected = false, onClick = onCity,
            icon = { Icon(Icons.Rounded.LocationOn, null) }, label = { Text("Місто") },
        )
        NavigationBarItem(
            selected = false, onClick = onSite,
            icon = { Icon(Icons.Rounded.Storefront, null) }, label = { Text("Сайт") },
        )
    }
}

@Composable
private fun AppNavHost(nav: NavHostController, modifier: Modifier) {
    NavHost(nav, startDestination = Routes.HOME, modifier = modifier) {
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
