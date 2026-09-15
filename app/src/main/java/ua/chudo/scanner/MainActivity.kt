package ua.chudo.scanner

import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.ui.auth.AuthGate
import ua.chudo.scanner.ui.common.RegionPickerSheet
import ua.chudo.scanner.i18n.LocalLang
import ua.chudo.scanner.i18n.tr
import ua.chudo.scanner.ui.layout.StoreLayoutScreen
import ua.chudo.scanner.ui.profile.ProfileScreen
import ua.chudo.scanner.ui.stock.StockScreen
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
            val lang by chudoApp.prefs.lang.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalLang provides lang) {
                ChudoTheme {
                    Surface(color = MaterialTheme.colorScheme.background) { AuthGate { AppNav(it) } }
                }
            }
        }
    }
}

private const val STOCK_MODE = "stock"
private const val STOCK_CODE = "stock_code"

private object Routes {
    const val HOME = "home"
    const val SCAN = "scan?mode={mode}"
    const val RESULTS = "results/{q}"
    const val PRODUCT = "product/{urlKey}?bc={bc}"
    const val STOCK = "stock"
    const val LAYOUT = "layout"
    const val PROFILE = "profile"

    const val SCAN_STOCK = "scan?mode=$STOCK_MODE"

    fun scan(mode: ScanMode = ScanMode.BARCODE) = "scan?mode=${mode.name}"
    fun results(q: String) = "results/${Uri.encode(q)}"
    fun product(urlKey: String, bc: String?) = "product/${Uri.encode(urlKey)}" + (bc?.let { "?bc=$it" } ?: "")
}

private enum class Tab { SEARCH, STOCK, LAYOUT }

@Composable
private fun AppNav(employee: Employee) {
    val nav = rememberNavController()
    val prefs = LocalContext.current.chudoApp.prefs
    val region by prefs.region.collectAsStateWithLifecycle()
    var showRegions by rememberSaveable { mutableStateOf(false) }
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val tab = when (route) {
        Routes.STOCK -> Tab.STOCK
        Routes.LAYOUT -> Tab.LAYOUT
        else -> Tab.SEARCH
    }
    fun openTab(r: String) {
        if (route != r) nav.navigate(r) { popUpTo(Routes.HOME); launchSingleTop = true }
    }

    Scaffold(
        // Отступы системных панелей обрабатывают сами экраны; здесь — только высота нижней панели.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // Сканер и профиль — без нижней навигации.
            if (route != null && route != Routes.SCAN && route != Routes.PROFILE) BottomTabs(
                tab = tab,
                onSearch = { if (route != Routes.HOME) nav.popBackStack(Routes.HOME, inclusive = false) },
                onScan = { nav.navigate(Routes.scan()) },
                onStock = { openTab(Routes.STOCK) },
                onLayout = { openTab(Routes.LAYOUT) },
                onCity = { showRegions = true },
            )
        },
    ) { inner ->
        AppNavHost(nav, employee, Modifier.padding(bottom = inner.calculateBottomPadding()).consumeWindowInsets(inner))
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

/** Пошук · Сканер · Залишки · Планування · Місто. */
@Composable
private fun BottomTabs(
    tab: Tab,
    onSearch: () -> Unit,
    onScan: () -> Unit,
    onStock: () -> Unit,
    onLayout: () -> Unit,
    onCity: () -> Unit,
) {
    NavigationBar {
        NavigationBarItem(
            selected = tab == Tab.SEARCH, onClick = onSearch,
            icon = { Icon(Icons.Rounded.Search, null) }, label = { Text(tr("Пошук", "Поиск")) },
        )
        NavigationBarItem(
            selected = false, onClick = onScan,
            icon = { Icon(Icons.Rounded.QrCodeScanner, null) }, label = { Text(tr("Сканер", "Сканер")) },
        )
        NavigationBarItem(
            selected = tab == Tab.STOCK, onClick = onStock,
            icon = { Icon(Icons.Rounded.Inventory2, null) }, label = { Text(tr("Залишки", "Остатки")) },
        )
        NavigationBarItem(
            selected = tab == Tab.LAYOUT, onClick = onLayout,
            icon = { Icon(Icons.Rounded.Map, null) }, label = { Text(tr("Планування", "Планировка"), maxLines = 1) },
        )
        NavigationBarItem(
            selected = false, onClick = onCity,
            icon = { Icon(Icons.Rounded.LocationOn, null) }, label = { Text(tr("Місто", "Город")) },
        )
    }
}

@Composable
private fun AppNavHost(nav: NavHostController, employee: Employee, modifier: Modifier) {
    NavHost(nav, startDestination = Routes.HOME, modifier = modifier) {
        composable(Routes.STOCK) { entry ->
            val scanned by entry.savedStateHandle.getStateFlow<String?>(STOCK_CODE, null).collectAsStateWithLifecycle()
            StockScreen(
                employee,
                onProfile = { nav.navigate(Routes.PROFILE) },
                onScan = { nav.navigate(Routes.SCAN_STOCK) },
                scanned = scanned,
                onScannedConsumed = { entry.savedStateHandle[STOCK_CODE] = null },
            )
        }
        composable(Routes.PROFILE) {
            val ctx = LocalContext.current
            val soon = tr("Налаштування принтерів з'являться в наступному оновленні.", "Настройка принтеров появится в следующем обновлении.")
            ProfileScreen(
                employee,
                onBack = { nav.popBackStack() },
                // Экран «Принтери» — этап 4.
                onPrinters = { Toast.makeText(ctx, soon, Toast.LENGTH_SHORT).show() },
            )
        }
        composable(Routes.LAYOUT) { StoreLayoutScreen() }
        composable(Routes.HOME) {
            HomeScreen(
                onScan = { nav.navigate(Routes.scan()) },
                onPhoto = { nav.navigate(Routes.scan(ScanMode.PHOTO)) },
                onSearch = { nav.navigate(Routes.results(it)) },
                onOpenProduct = { nav.navigate(Routes.product(it, null)) },
                onProfile = { nav.navigate(Routes.PROFILE) },
            )
        }
        composable(
            Routes.SCAN,
            arguments = listOf(navArgument("mode") { type = NavType.StringType; defaultValue = ScanMode.BARCODE.name }),
        ) { entry ->
            val app = LocalContext.current.chudoApp
            val arg = entry.arguments?.getString("mode").orEmpty()
            // mode=stock — скан для «Залишків»: код возвращаем в поиск остатков.
            val forStock = arg == STOCK_MODE
            val mode = runCatching { ScanMode.valueOf(arg) }.getOrDefault(ScanMode.BARCODE)
            ScannerScreen(
                startMode = mode,
                onBarcode = { code ->
                    if (forStock) {
                        nav.previousBackStackEntry?.savedStateHandle?.set(STOCK_CODE, code)
                        nav.popBackStack()
                    } else {
                        nav.navigate(Routes.results(code)) { popUpTo(Routes.SCAN) { inclusive = true } }
                    }
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
