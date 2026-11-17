package ua.chudo.scanner

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import ua.chudo.scanner.ui.orders.OrdersScreen
import ua.chudo.scanner.ui.print.LabelsScreen
import ua.chudo.scanner.ui.print.PrintersScreen
import ua.chudo.scanner.ui.profile.ProfileScreen
import ua.chudo.scanner.ui.stock.StockScreen
import ua.chudo.scanner.ui.home.HomeScreen
import ua.chudo.scanner.ui.product.ProductScreen
import ua.chudo.scanner.ui.results.ResultsScreen
import ua.chudo.scanner.ui.scan.ScannerScreen
import ua.chudo.scanner.ui.common.MainTab
import ua.chudo.scanner.ui.common.MineBottomBar
import ua.chudo.scanner.ui.theme.ChudoTheme
import ua.chudo.scanner.ui.theme.isDarkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Тема из профиля может не совпадать с системной — значки статус-бара под неё.
            val dark = isDarkTheme()
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            ChudoTheme(dark) {
                Surface(color = MaterialTheme.colorScheme.background) { AuthGate { AppNav(it) } }
            }
        }
    }
}

private const val STOCK_MODE = "stock"
private const val STOCK_CODE = "stock_code"
private const val LABEL_MODE = "label"
private const val LABEL_CODE = "label_code"
private const val ORDERS_MODE = "orders"
private const val ORDERS_CODE = "orders_code"

private object Routes {
    const val HOME = "home"
    const val SCAN = "scan?mode={mode}"
    const val RESULTS = "results/{q}"
    const val PRODUCT = "product/{urlKey}?bc={bc}"
    const val STOCK = "stock"
    const val ORDERS = "orders"
    const val PROFILE = "profile"

    const val SCAN_STOCK = "scan?mode=$STOCK_MODE"
    const val SCAN_LABEL = "scan?mode=$LABEL_MODE"
    const val SCAN_ORDERS = "scan?mode=$ORDERS_MODE"
    const val LABELS = "labels"
    const val PRINTERS = "printers"

    fun scan() = "scan?mode=barcode"
    fun results(q: String) = "results/${Uri.encode(q)}"
    fun product(urlKey: String, bc: String?) = "product/${Uri.encode(urlKey)}" + (bc?.let { "?bc=$it" } ?: "")
}

private val FULLSCREEN = setOf(Routes.SCAN, Routes.PRINTERS)


@Composable
private fun AppNav(employee: Employee) {
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val printing = LocalContext.current.chudoApp.printing
    val ordersRepo = LocalContext.current.chudoApp.orders
    LaunchedEffect(employee.shopId) { ordersRepo.load(employee.shopId) }
    val orders by ordersRepo.orders.collectAsStateWithLifecycle()
    val newOrders = orders.count { it.stage == ua.chudo.scanner.data.OrderStage.NEW }
    // Магазин — для печати через ПК магазина; сохранённые принтеры, сменившие IP, тихо находим заново.
    LaunchedEffect(employee.shopId) {
        printing.attach(employee.shopId)
        printing.syncOnStart()
    }
    // Залишки открываются из профиля — подсвечиваем «Профіль»; результаты и карточка товара — «Пошук».
    val tab = when (route) {
        Routes.ORDERS -> MainTab.ORDERS
        Routes.LABELS -> MainTab.PRINT
        Routes.PROFILE, Routes.STOCK -> MainTab.PROFILE
        else -> MainTab.SEARCH
    }
    fun openTab(r: String) {
        if (route != r) nav.navigate(r) { popUpTo(Routes.HOME); launchSingleTop = true }
    }

    Scaffold(
        // Отступы системных панелей обрабатывают сами экраны; здесь — только высота нижней панели.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // Сканер и настройки принтеров — без нижней навигации.
            if (route != null && route !in FULLSCREEN) MineBottomBar(
                tab = tab,
                newOrders = newOrders,
                onTab = { t ->
                    when (t) {
                        MainTab.SEARCH -> if (route != Routes.HOME) nav.popBackStack(Routes.HOME, inclusive = false)
                        MainTab.ORDERS -> openTab(Routes.ORDERS)
                        MainTab.PRINT -> openTab(Routes.LABELS)
                        MainTab.PROFILE -> openTab(Routes.PROFILE)
                    }
                },
                onScan = { nav.navigate(Routes.scan()) },
            )
        },
    ) { inner ->
        AppNavHost(nav, employee, Modifier.padding(bottom = inner.calculateBottomPadding()).consumeWindowInsets(inner))
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
            ProfileScreen(
                employee, onBack = { nav.popBackStack() },
                onPrinters = if (employee.admin) ({ nav.navigate(Routes.PRINTERS) }) else null,
                onStock = { nav.navigate(Routes.STOCK) },
            )
        }
        // «Принтери» — только администратору: сотрудник принтер не выбирает и не настраивает.
        composable(Routes.PRINTERS) { if (employee.admin) PrintersScreen(employee, onBack = { nav.popBackStack() }) else nav.popBackStack() }
        composable(Routes.LABELS) { entry ->
            val scanned by entry.savedStateHandle.getStateFlow<String?>(LABEL_CODE, null).collectAsStateWithLifecycle()
            LabelsScreen(
                onBack = { nav.popBackStack() },
                onScan = { nav.navigate(Routes.SCAN_LABEL) },
                onPrinters = if (employee.admin) ({ nav.navigate(Routes.PRINTERS) }) else null,
                scanned = scanned,
                onScannedConsumed = { entry.savedStateHandle[LABEL_CODE] = null },
            )
        }
        composable(Routes.ORDERS) { entry ->
            val scanned by entry.savedStateHandle.getStateFlow<String?>(ORDERS_CODE, null).collectAsStateWithLifecycle()
            OrdersScreen(
                employee,
                onScan = { nav.navigate(Routes.SCAN_ORDERS) },
                scanned = scanned,
                onScannedConsumed = { entry.savedStateHandle[ORDERS_CODE] = null },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onScan = { nav.navigate(Routes.scan()) },
                onSearch = { nav.navigate(Routes.results(it)) },
                onOpenProduct = { nav.navigate(Routes.product(it, null)) },
                onProfile = { nav.navigate(Routes.PROFILE) },
                onLabels = { nav.navigate(Routes.LABELS) },
            )
        }
        composable(
            Routes.SCAN,
            arguments = listOf(navArgument("mode") { type = NavType.StringType; defaultValue = "barcode" }),
        ) { entry ->
            val arg = entry.arguments?.getString("mode").orEmpty()
            // mode=stock / mode=label — код возвращаем на предыдущий экран («Залишки» / «Друк цінників»).
            val returnKey = when (arg) {
                STOCK_MODE -> STOCK_CODE
                LABEL_MODE -> LABEL_CODE
                ORDERS_MODE -> ORDERS_CODE
                else -> null
            }
            ScannerScreen(
                onBarcode = { code ->
                    if (returnKey != null) {
                        nav.previousBackStackEntry?.savedStateHandle?.set(returnKey, code)
                        nav.popBackStack()
                    } else {
                        nav.navigate(Routes.results(code)) { popUpTo(Routes.SCAN) { inclusive = true } }
                    }
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
