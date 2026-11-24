package ua.chudo.scanner

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.remember
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
import ua.chudo.scanner.ui.print.PrintersScreen
import ua.chudo.scanner.ui.stock.NetworkStockScreen
import ua.chudo.scanner.ui.admin.AdminHistoryScreen
import ua.chudo.scanner.ui.admin.NetworkAdminScreen
import ua.chudo.scanner.ui.admin.ShopsAdminScreen
import ua.chudo.scanner.ui.admin.StaffAdminScreen
import ua.chudo.scanner.data.ShopEntry
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
private const val ORDERS_MODE = "orders"
private const val ORDERS_CODE = "orders_code"
private const val TERMINAL_MODE = "terminal"
private const val TERMINAL_CODE = "terminal_code"

private object Routes {
    const val HOME = "home"
    const val SCAN = "scan?mode={mode}"
    const val RESULTS = "results/{q}"
    const val PRODUCT = "product/{urlKey}?bc={bc}"
    const val STOCK = "stock"
    const val ORDERS = "orders"
    const val PROFILE = "profile"
    const val TERMINAL = "terminal"

    const val SCAN_STOCK = "scan?mode=$STOCK_MODE"
    const val SCAN_ORDERS = "scan?mode=$ORDERS_MODE"
    const val SCAN_TERMINAL = "scan?mode=$TERMINAL_MODE"
    const val LABELS = "labels"
    const val PRINTERS = "printers"
    const val NETWORK_STOCK = "network-stock"
    const val ADMIN = "admin"
    const val ADMIN_SHOPS = "admin-shops"
    const val ADMIN_STAFF = "admin-staff?shop={shop}"
    const val ADMIN_HISTORY = "admin-history"
    const val MY_SHOP = "my-shop"

    fun adminStaff(shop: String? = null) = "admin-staff" + (shop?.let { "?shop=" + Uri.encode(it) } ?: "")

    fun scan() = "scan?mode=barcode"
    fun results(q: String) = "results/${Uri.encode(q)}"
    fun product(urlKey: String, bc: String?) = "product/${Uri.encode(urlKey)}" + (bc?.let { "?bc=$it" } ?: "")
}

// Сканер, настройки принтеров и карточка товара (у неё своя панель «Друкувати цінник · Сканувати ще») — без нижней навигации.
private val FULLSCREEN = setOf(Routes.SCAN, Routes.PRINTERS, Routes.PRODUCT)


@Composable
private fun AppNav(home: Employee) {
    val nav = rememberNavController()
    // «Працювати як у магазині ЧО NN» (лише superAdmin): лише в пам'яті, після виходу не зберігається.
    var acting by remember(home.uid) { mutableStateOf<Employee?>(null) }
    val employee = acting ?: home
    fun switchShop(e: Employee?) {
        acting = e
        nav.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } }
    }
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val printing = LocalContext.current.chudoApp.printing
    val ordersRepo = LocalContext.current.chudoApp.orders
    LaunchedEffect(employee.shopId) { ordersRepo.load(employee.shopId) }
    // Пакет залишків мережі — одразу після входу, у фоні.
    val appCtx = LocalContext.current.applicationContext
    LaunchedEffect(home.uid) { ua.chudo.scanner.data.StockPrefetchWorker.schedule(appCtx) }
    // Оплати, що зависли в "paying" (збій, перезапуск), — питаємо банк, без повторного списання.
    val chudo = LocalContext.current.chudoApp
    LaunchedEffect(employee.shopId) { if (chudo.auth.configured) chudo.payments.recover(employee.shopId, home.uid) }
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
        Routes.STOCK -> MainTab.STOCK
        Routes.TERMINAL -> MainTab.TERMINAL
        Routes.PROFILE -> MainTab.PROFILE
        else -> MainTab.SEARCH
    }
    fun openTab(r: String) {
        if (route != r) nav.navigate(r) { popUpTo(Routes.HOME); launchSingleTop = true }
    }

    Scaffold(
        // Отступы системных панелей обрабатывают сами экраны; здесь — только высота нижней панели.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Column {
            acting?.let { a -> ActingBanner(a, withInsets = route in FULLSCREEN) { switchShop(null) } }
            // Сканер и настройки принтеров — без нижней навигации.
            if (route != null && route !in FULLSCREEN) MineBottomBar(
                tab = tab,
                newOrders = newOrders,
                onTab = { t ->
                    when (t) {
                        MainTab.SEARCH -> if (route != Routes.HOME) nav.popBackStack(Routes.HOME, inclusive = false)
                        MainTab.ORDERS -> openTab(Routes.ORDERS)
                        MainTab.STOCK -> openTab(Routes.STOCK)
                        MainTab.TERMINAL -> openTab(Routes.TERMINAL)
                        MainTab.PROFILE -> openTab(Routes.PROFILE)
                    }
                },
                onScan = { nav.navigate(Routes.scan()) },
            )
            }
        },
    ) { inner ->
        AppNavHost(
            nav, employee, home, Modifier.padding(bottom = inner.calculateBottomPadding()).consumeWindowInsets(inner),
            onActAs = { e -> e.shopId?.let { id -> switchShop(home.actingIn(id, e.address, e.region, e.code)) } },
        )
    }
}

@Composable
private fun AppNavHost(nav: NavHostController, employee: Employee, home: Employee, modifier: Modifier, onActAs: (ShopEntry) -> Unit) {
    NavHost(nav, startDestination = Routes.HOME, modifier = modifier) {
        composable(Routes.STOCK) { entry ->
            val scanned by entry.savedStateHandle.getStateFlow<String?>(STOCK_CODE, null).collectAsStateWithLifecycle()
            StockScreen(
                employee,
                onBack = { nav.popBackStack() },
                onScan = { nav.navigate(Routes.SCAN_STOCK) },
                scanned = scanned,
                onScannedConsumed = { entry.savedStateHandle[STOCK_CODE] = null },
                onNetworkUpload = if (employee.stockAdmin) ({ nav.navigate(Routes.NETWORK_STOCK) }) else null,
            )
        }
        composable(Routes.PROFILE) {
            ProfileScreen(
                employee, onBack = { nav.popBackStack() },
                onPrinters = if (employee.managesShop) ({ nav.navigate(Routes.PRINTERS) }) else null,
                onStock = { nav.navigate(Routes.STOCK) },
                onNetworkStock = if (employee.stockAdmin) ({ nav.navigate(Routes.NETWORK_STOCK) }) else null,
                // Адмін мережі — «Адміністрування мережі»; адмін магазину — лише «Мій магазин».
                onNetworkAdmin = if (home.superAdmin) ({ nav.navigate(Routes.ADMIN) }) else null,
                onMyShop = if (home.admin && !home.superAdmin) ({ nav.navigate(Routes.MY_SHOP) }) else null,
            )
        }
        composable(Routes.ADMIN) {
            if (home.superAdmin) NetworkAdminScreen(
                home, onBack = { nav.popBackStack() },
                onShops = { nav.navigate(Routes.ADMIN_SHOPS) }, onStaff = { nav.navigate(Routes.adminStaff()) },
                onNetworkStock = { nav.navigate(Routes.NETWORK_STOCK) }, onHistory = { nav.navigate(Routes.ADMIN_HISTORY) },
            ) else nav.popBackStack()
        }
        composable(Routes.ADMIN_SHOPS) {
            if (home.superAdmin) ShopsAdminScreen(home, onBack = { nav.popBackStack() }, onActAs = onActAs, onStaffOf = { nav.navigate(Routes.adminStaff(it)) })
            else nav.popBackStack()
        }
        composable(Routes.ADMIN_STAFF, arguments = listOf(navArgument("shop") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
            if (home.superAdmin) StaffAdminScreen(home, onBack = { nav.popBackStack() }, shopFilter = entry.arguments?.getString("shop"))
            else nav.popBackStack()
        }
        composable(Routes.ADMIN_HISTORY) { if (home.superAdmin) AdminHistoryScreen(home, onBack = { nav.popBackStack() }) else nav.popBackStack() }
        composable(Routes.MY_SHOP) {
            if (home.admin) StaffAdminScreen(home, onBack = { nav.popBackStack() }, ownShopOnly = true) else nav.popBackStack()
        }
        // «Залишки мережі» — лише stockAdmin / superAdmin (те саме перевіряють правила).
        composable(Routes.NETWORK_STOCK) {
            if (employee.stockAdmin) NetworkStockScreen(employee, onBack = { nav.popBackStack() }) else nav.popBackStack()
        }
        // «Принтери» — только администратору: сотрудник принтер не выбирает и не настраивает.
        composable(Routes.PRINTERS) { if (employee.managesShop) PrintersScreen(employee, onBack = { nav.popBackStack() }) else nav.popBackStack() }
        // Старий екран «Друк» прибрано: цінник — з картки товару, пакет — із «Залишків». Старі посилання ведуть туди.
        composable(Routes.LABELS) {
            LaunchedEffect(Unit) { nav.navigate(Routes.STOCK) { popUpTo(Routes.LABELS) { inclusive = true } } }
        }
        composable(Routes.TERMINAL) { entry ->
            val scanned by entry.savedStateHandle.getStateFlow<String?>(TERMINAL_CODE, null).collectAsStateWithLifecycle()
            ua.chudo.scanner.ui.terminal.TerminalScreen(
                employee,
                onScan = { nav.navigate(Routes.SCAN_TERMINAL) },
                scanned = scanned,
                onScannedConsumed = { entry.savedStateHandle[TERMINAL_CODE] = null },
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
                employee,
                onSearch = { nav.navigate(Routes.results(it)) },
                onOpenProduct = { nav.navigate(Routes.product(it, null)) },
                onProfile = { nav.navigate(Routes.PROFILE) },
                onLabels = { nav.navigate(Routes.STOCK) },
                onOrders = { nav.navigate(Routes.ORDERS) },
                onStock = { nav.navigate(Routes.STOCK) },
            )
        }
        composable(
            Routes.SCAN,
            arguments = listOf(navArgument("mode") { type = NavType.StringType; defaultValue = "barcode" }),
        ) { entry ->
            val arg = entry.arguments?.getString("mode").orEmpty()
            // mode=stock / mode=orders — код повертаємо на попередній екран («Залишки» / «Замовлення»).
            val returnKey = when (arg) {
                STOCK_MODE -> STOCK_CODE
                ORDERS_MODE -> ORDERS_CODE
                TERMINAL_MODE -> TERMINAL_CODE
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
                shopId = employee.shopId,
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
            ProductScreen(employee, onBack = { nav.popBackStack() }, onScan = { nav.navigate(Routes.scan()) })
        }
    }
}

/** «Ви в магазині ЧО NN · Повернутися» — поки адмін мережі працює як у іншому магазині. */
@Composable
private fun ActingBanner(e: Employee, withInsets: Boolean, onReturn: () -> Unit) {
    val m = ua.chudo.scanner.ui.theme.Mine.colors
    androidx.compose.material3.Surface(color = m.accent, contentColor = m.onAccent) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().then(if (withInsets) Modifier.navigationBarsPadding() else Modifier).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Text(
                "Ви в магазині ЧО ${e.actingCode}" + (e.shop.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                Modifier.weight(1f), fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            androidx.compose.material3.TextButton(onClick = onReturn) {
                androidx.compose.material3.Text("Повернутися", color = m.onAccent, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
        }
    }
}
