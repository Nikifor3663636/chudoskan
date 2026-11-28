package ua.chudo.scanner

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.key
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
import ua.chudo.scanner.data.OrderStage
import ua.chudo.scanner.ui.auth.AuthGate
import ua.chudo.scanner.ui.common.RegionPickerSheet
import ua.chudo.scanner.ui.orders.OrdersScreen
import ua.chudo.scanner.ui.print.PrintersScreen
import ua.chudo.scanner.data.Backend
import ua.chudo.scanner.data.HighlightKind
import ua.chudo.scanner.ui.stock.HighlightsScreen
import ua.chudo.scanner.ui.stock.NetworkStockScreen
import ua.chudo.scanner.ui.admin.AdminHistoryScreen
import ua.chudo.scanner.ui.admin.NetworkAdminScreen
import ua.chudo.scanner.ui.admin.ShopsAdminScreen
import ua.chudo.scanner.ui.admin.StaffAdminScreen
import ua.chudo.scanner.data.ShopEntry
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.chudo.scanner.ui.profile.ProfileScreen
import ua.chudo.scanner.ui.stock.LabelBatchScreen
import ua.chudo.scanner.ui.stock.rememberStockViewModel
import ua.chudo.scanner.ui.stock.StockScreen
import ua.chudo.scanner.ui.home.HistoryScreen
import ua.chudo.scanner.ui.home.HomeScreen
import ua.chudo.scanner.ui.product.ProductScreen
import ua.chudo.scanner.ui.results.CatalogScreen
import ua.chudo.scanner.ui.results.ResultsScreen
import ua.chudo.scanner.ui.scan.ProductScanScreen
import ua.chudo.scanner.ui.scan.ScannerScreen
import ua.chudo.scanner.ui.common.MainTab
import ua.chudo.scanner.ui.common.TabletRail
import ua.chudo.scanner.ui.common.TabletPanes
import ua.chudo.scanner.ui.common.TabletMinWidth
import ua.chudo.scanner.ui.product.TabletProduct
import ua.chudo.scanner.ui.common.MineBottomBar
import ua.chudo.scanner.ui.theme.ChudoTheme
import ua.chudo.scanner.ui.theme.Mine
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
/** Кошик: сканер не закривається, кожен код одразу йде в кошик. */
private const val CART_MODE = "cart"

private object Routes {
    const val HOME = "home"
    const val SCAN = "scan?mode={mode}"
    const val RESULTS = "results?q={q}&cat={cat}"
    const val CATALOG = "catalog"
    const val PRODUCT = "product/{urlKey}?bc={bc}&scanned={scanned}&print={print}"
    const val STOCK = "stock"
    const val ORDERS = "orders"
    const val PROFILE = "profile"
    const val SCANNER_SETTINGS = "scanner-settings"
    const val OPERATIONS = "operations"
    const val NOTIFICATIONS = "notifications"

    const val SCAN_STOCK = "scan?mode=$STOCK_MODE"
    const val SCAN_ORDERS = "scan?mode=$ORDERS_MODE"
    const val SCAN_CART = "scan?mode=$CART_MODE"
    const val HISTORY = "history"
    const val CART = "cart"
    const val LABELS = "labels"
    const val PRINTERS = "printers"
    const val NETWORK_STOCK = "network-stock"
    const val HIGHLIGHTS = "highlights?kind={kind}"
    fun highlights(kind: HighlightKind = HighlightKind.NEW) = "highlights?kind=${kind.key}"
    const val ADMIN = "admin"
    const val ADMIN_SHOPS = "admin-shops"
    const val ADMIN_STAFF = "admin-staff?shop={shop}"
    const val ADMIN_HISTORY = "admin-history"
    const val MY_SHOP = "my-shop"

    fun adminStaff(shop: String? = null) = "admin-staff" + (shop?.let { "?shop=" + Uri.encode(it) } ?: "")

    fun scan(price: Boolean = false) = if (price) "scan?mode=price" else "scan?mode=barcode"
    fun results(q: String) = if (q.isEmpty()) "results" else "results?q=${Uri.encode(q)}"
    fun category(uid: String, title: String) = "results?q=${Uri.encode(title)}&cat=${Uri.encode(uid)}"
    fun product(urlKey: String, bc: String?, scanned: String? = null, print: Boolean = false): String {
        val query = listOfNotNull(
            bc?.let { "bc=${Uri.encode(it)}" }, scanned?.let { "scanned=${Uri.encode(it)}" }, "print=true".takeIf { print },
        )
        return "product/${Uri.encode(urlKey)}" + if (query.isEmpty()) "" else query.joinToString("&", prefix = "?")
    }
}

// Сканер, настройки принтеров, карточка товара (у неё своя панель «Друкувати цінник · Сканувати ще»)
// и кошик (своя панель «Разом · Сканувати ще») — без нижней навигации.
private val FULLSCREEN = setOf(Routes.SCAN, Routes.PRINTERS, Routes.PRODUCT, Routes.CART, Routes.LABELS)


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
    val orders by ordersRepo.orders.collectAsStateWithLifecycle()
    LaunchedEffect(employee.shopId) { ordersRepo.load(employee.shopId) }
    // Магазин — для печати через ПК магазина; сохранённые принтеры, сменившие IP, тихо находим заново.
    LaunchedEffect(employee.shopId) {
        printing.attach(employee.shopId)
        printing.syncOnStart()
    }
    val cart = LocalContext.current.chudoApp.cart
    LaunchedEffect(employee.shopId) { cart.attach(employee.shopId) }
    // Нова версія на GitHub — банер «Оновити» на головній (перевірка не частіше ніж раз на 3 години).
    val updater = LocalContext.current.chudoApp.updater
    LaunchedEffect(Unit) { updater.check() }
    val tab = when (route) {
        Routes.HOME, Routes.RESULTS, Routes.CATALOG, Routes.HISTORY -> MainTab.HOME
        Routes.ORDERS -> MainTab.ORDERS
        Routes.STOCK, Routes.HIGHLIGHTS -> MainTab.STOCK
        Routes.PROFILE -> MainTab.PROFILE
        else -> null
    }
    fun openTab(r: String) {
        if (route != r) nav.navigate(r) { popUpTo(Routes.HOME); launchSingleTop = true }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tablet = maxWidth >= TabletMinWidth
        val railVisible = tablet && route != null && route != Routes.SCAN
        val onTab: (MainTab) -> Unit = { selected ->
            when (selected) {
                MainTab.HOME -> if (tablet) {
                    if (route != Routes.RESULTS) openTab(Routes.results(""))
                } else if (route != Routes.HOME) {
                    nav.popBackStack(Routes.HOME, inclusive = false)
                }
                MainTab.ORDERS -> openTab(Routes.ORDERS)
                MainTab.STOCK -> openTab(Routes.STOCK)
                MainTab.PROFILE -> openTab(Routes.PROFILE)
            }
        }
        val onScan = {
            nav.navigate(if (route == Routes.STOCK) Routes.SCAN_STOCK else Routes.scan())
        }
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                Column {
                    acting?.let { a -> ActingBanner(a, withInsets = railVisible || route in FULLSCREEN) { switchShop(null) } }
                    if (!tablet && route != null && route !in FULLSCREEN) MineBottomBar(
                        tab = tab,
                        newOrders = orders.count { it.stage == OrderStage.NEW },
                        onTab = onTab,
                        scanLabel = if (route == Routes.STOCK) "Сканувати для пошуку залишків" else "Сканувати товар",
                        onScan = onScan,
                    )
                }
            },
        ) { inner ->
            Row(Modifier.fillMaxSize().padding(bottom = inner.calculateBottomPadding()).consumeWindowInsets(inner)) {
                if (railVisible) TabletRail(
                    selected = tab, printing = route == Routes.LABELS,
                    onTab = onTab, onScan = onScan, onPrint = { openTab(Routes.LABELS) },
                )
                AppNavHost(
                    nav, employee, home, Modifier.weight(1f), tablet = tablet,
                    onActAs = { e -> e.shopId?.let { id -> switchShop(home.actingIn(id, e.address, e.region, e.code)) } },
                )
            }
        }
    }
}

@Composable
private fun AppNavHost(
    nav: NavHostController, employee: Employee, home: Employee, modifier: Modifier,
    tablet: Boolean, onActAs: (ShopEntry) -> Unit,
) {
    fun setCatalog(entry: androidx.navigation.NavBackStackEntry, items: List<String>, selected: String) {
        entry.savedStateHandle["catalogBrowse"] = ArrayList(items)
        entry.savedStateHandle["catalogIndex"] = items.indexOf(selected)
    }
    val wide by androidx.compose.runtime.rememberUpdatedState(tablet)
    var searchHistory by rememberSaveable(employee.uid) { mutableStateOf<List<String>>(arrayListOf()) }
    NavHost(nav, startDestination = Routes.HOME, modifier = modifier) {
        composable(Routes.STOCK) { entry ->
            val homeEntry = remember(entry) { nav.getBackStackEntry(Routes.HOME) }
            val scanned by entry.savedStateHandle.getStateFlow<String?>(STOCK_CODE, null).collectAsStateWithLifecycle()
            StockScreen(
                employee,
                onBack = { nav.popBackStack() },
                vm = rememberStockViewModel(employee, homeEntry),
                scanned = scanned,
                onScannedConsumed = { entry.savedStateHandle[STOCK_CODE] = null },
                onOpenCatalogProduct = { barcode, items ->
                    setCatalog(entry, items, barcode)
                    nav.navigate(Routes.results(barcode))
                },
                onNetworkUpload = if (employee.stockAdmin) ({ nav.navigate(Routes.NETWORK_STOCK) }) else null,
            )
        }
        composable(Routes.OPERATIONS) {
            ua.chudo.scanner.ui.profile.ActivityScreen(employee, { nav.popBackStack() })
        }
        composable(Routes.NOTIFICATIONS) {
            ua.chudo.scanner.ui.profile.ActivityScreen(employee, { nav.popBackStack() }, notifications = true)
        }
        composable(Routes.SCANNER_SETTINGS) {
            ua.chudo.scanner.ui.profile.ScannerSettingsScreen(employee) { nav.popBackStack() }
        }
        composable(Routes.PROFILE) {
            ProfileScreen(
                employee, onBack = { nav.popBackStack() },
                onScannerSettings = { nav.navigate(Routes.SCANNER_SETTINGS) },
                onOperations = { nav.navigate(Routes.OPERATIONS) },
                onPrinters = if (employee.managesShop) ({ nav.navigate(Routes.PRINTERS) }) else null,
                // Адмін мережі — «Адміністрування мережі»; адмін магазину — лише «Мій магазин».
                onNetworkAdmin = if (home.superAdmin) ({ nav.navigate(Routes.ADMIN) }) else null,
                onMyShop = if (home.admin && !home.superAdmin) ({ nav.navigate(Routes.MY_SHOP) }) else null,
            )
        }
        composable(Routes.ADMIN) {
            if (home.superAdmin) NetworkAdminScreen(
                home, onBack = { nav.popBackStack() },
                onShops = { nav.navigate(Routes.ADMIN_SHOPS) }, onStaff = { nav.navigate(Routes.adminStaff()) },
                onHistory = { nav.navigate(Routes.ADMIN_HISTORY) },
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
            if (employee.stockAdmin) NetworkStockScreen(
                employee, onBack = { nav.popBackStack() },
            ) else nav.popBackStack()
        }
        composable(Routes.HIGHLIGHTS, arguments = listOf(navArgument("kind") { type = NavType.StringType; defaultValue = "new" })) { entry ->
            val kind = HighlightKind.entries.firstOrNull { it.key == entry.arguments?.getString("kind") } ?: HighlightKind.NEW
            if (Backend.isSupabase) HighlightsScreen(
                canUpload = employee.stockAdmin || employee.superAdmin,
                initialKind = kind,
                onPromos = { nav.navigate(Routes.highlights(HighlightKind.PROMO)) },
                onNew = {
                    val route = Routes.highlights(HighlightKind.NEW)
                    if (!nav.popBackStack(route, inclusive = false)) {
                        nav.navigate(route) { popUpTo(Routes.HIGHLIGHTS) { inclusive = true } }
                    }
                },
                onBack = { nav.popBackStack() },
                onProduct = { key, barcode -> nav.navigate(Routes.product(key, null, barcode)) },
                onBrowseCatalog = { items, selected -> setCatalog(entry, items, selected) },
                onSearch = { nav.navigate(Routes.results(it)) },
            )
        }
        // «Принтери» — только администратору: сотрудник принтер не выбирает и не настраивает.
        composable(Routes.PRINTERS) { if (employee.managesShop) PrintersScreen(employee, onBack = { nav.popBackStack() }) else nav.popBackStack() }
        composable(Routes.LABELS) { entry ->
            val homeEntry = remember(entry) { nav.getBackStackEntry(Routes.HOME) }
            LabelBatchScreen(
                employee = employee,
                vm = rememberStockViewModel(employee, homeEntry),
                onBack = { nav.popBackStack() },
                onFindProduct = { nav.navigate(Routes.STOCK) { popUpTo(Routes.HOME); launchSingleTop = true } },
            )
        }
        composable(Routes.ORDERS) { entry ->
            val scanned by entry.savedStateHandle.getStateFlow<String?>(ORDERS_CODE, null).collectAsStateWithLifecycle()
            OrdersScreen(
                employee,
                onBack = { nav.popBackStack() },
                onScan = { nav.navigate(Routes.SCAN_ORDERS) },
                scanned = scanned,
                onScannedConsumed = { entry.savedStateHandle[ORDERS_CODE] = null },
                tablet = wide,
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                employee,
                onScan = { nav.navigate(Routes.scan()) },
                onLabels = { nav.navigate(Routes.LABELS) },
                onSearch = { nav.navigate(Routes.results("")) },
                onOpenProduct = { nav.navigate(Routes.product(it, null)) },
                onHistory = { nav.navigate(Routes.HISTORY) },
                onNotifications = { nav.navigate(Routes.NOTIFICATIONS) },
                onOrders = { nav.navigate(Routes.ORDERS) },
                onCart = { nav.navigate(Routes.CART) },
                onHighlights = if (Backend.isSupabase) ({ kind -> nav.navigate(Routes.highlights(kind)) }) else null,
            )
        }
        composable(Routes.HISTORY) {
            HistoryScreen(
                onBack = { nav.popBackStack() },
                onOpenProduct = { nav.navigate(Routes.product(it, null)) },
            )
        }
        composable(Routes.CART) {
            ua.chudo.scanner.ui.cart.CartScreen(employee, onBack = { nav.popBackStack() }, onScan = { nav.navigate(Routes.SCAN_CART) })
        }
        composable(
            Routes.SCAN,
            arguments = listOf(navArgument("mode") { type = NavType.StringType; defaultValue = "barcode" }),
        ) { entry ->
            val arg = entry.arguments?.getString("mode").orEmpty()
            val cart = LocalContext.current.chudoApp.cart
            if (arg == CART_MODE) {
                val last by cart.last.collectAsStateWithLifecycle()
                val lines by cart.lines.collectAsStateWithLifecycle()
                var manual by rememberSaveable { mutableStateOf(false) }
                ScannerScreen(
                    onBarcode = { if (!manual) cart.add(it) },
                    onClose = { nav.popBackStack() },
                    continuous = true,
                    status = last,
                    cartUnits = ua.chudo.scanner.data.Cart.units(lines),
                    onManual = { manual = true },
                    acceptingScans = !manual,
                    onCartMode = {},
                    onPriceMode = { price ->
                        nav.navigate(Routes.scan(price)) { popUpTo(Routes.SCAN) { inclusive = true } }
                    },
                )
                if (manual) ua.chudo.scanner.ui.scan.ManualScanInput(
                    onClose = { manual = false },
                    onSubmit = { code -> cart.add(code); manual = false },
                )
                return@composable
            }
            // mode=stock / mode=orders — код повертаємо на попередній екран («Залишки» / «Замовлення»).
            val returnKey = when (arg) {
                STOCK_MODE -> STOCK_CODE
                ORDERS_MODE -> ORDERS_CODE
                else -> null
            }
            if (returnKey == null) {
                ProductScanScreen(
                    employee = employee,
                    onClose = { nav.popBackStack() },
                    onSearch = { code -> nav.navigate(Routes.results(code)) },
                    onProduct = { key, barcode -> nav.navigate(Routes.product(key, null, barcode)) },
                    onPrint = { key, barcode -> nav.navigate(Routes.product(key, null, barcode, print = true)) },
                    initialPriceMode = arg == "price",
                    onOpenSearch = { nav.navigate(Routes.results("")) },
                    onCartScan = {
                        nav.navigate(Routes.CART) {
                            popUpTo(Routes.SCAN) { inclusive = true }
                            launchSingleTop = true
                        }
                        nav.navigate(Routes.SCAN_CART)
                    },
                    onCart = { barcode ->
                        barcode?.let(cart::add)
                        nav.navigate(Routes.CART)
                    },
                )
                return@composable
            }
            ScannerScreen(
                onBarcode = { code ->
                    nav.previousBackStackEntry?.savedStateHandle?.set(returnKey, code)
                    nav.popBackStack()
                },
                onClose = { nav.popBackStack() },
            )
        }
        composable(Routes.CATALOG) {
            CatalogScreen(onBack = { nav.popBackStack() }, onCategory = { nav.navigate(Routes.category(it.uid, it.name)) })
        }
        composable(
            Routes.RESULTS,
            arguments = listOf(
                navArgument("q") { type = NavType.StringType; defaultValue = "" },
                navArgument("cat") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { resultsEntry ->
            var selection by rememberSaveable { mutableStateOf<List<String?>?>(null) }
            val results: @Composable () -> Unit = {
                ResultsScreen(
                    onBrowseProducts = { resultsEntry.savedStateHandle["productBrowse"] = ArrayList(it) },
                    searchHistory = searchHistory,
                    onQueryUsed = { query ->
                        searchHistory = ArrayList((listOf(query) + searchHistory.filterNot { it.equals(query, ignoreCase = true) }).take(12))
                    },
                    onClearHistory = { searchHistory = arrayListOf() },
                    onSearch = { query ->
                        nav.navigate(Routes.results(query)) { popUpTo(Routes.RESULTS) { inclusive = true } }
                    },
                    onCatalog = { nav.navigate(Routes.CATALOG) { launchSingleTop = true } },
                    employee = employee,
                    onBack = { nav.popBackStack() },
                    selectedProduct = selection?.firstOrNull().takeIf { wide },
                    tablet = wide,
                    onOpenProduct = { urlKey, bc, scanned, replace ->
                        if (wide) {
                            selection = arrayListOf(urlKey, bc, scanned)
                        } else {
                            nav.navigate(Routes.product(urlKey, bc, scanned)) {
                                if (replace) popUpTo(Routes.RESULTS) { inclusive = true }
                            }
                        }
                    },
                )
            }
            if (wide) TabletPanes(results) {
                val choice = selection
                if (choice == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Оберіть товар зі списку", color = Mine.colors.textSecondary)
                    }
                } else key(choice) {
                    TabletProduct(
                        employee, choice[0].orEmpty(), choice[1], choice[2],
                        onClose = { selection = null }, onScan = { nav.navigate(Routes.scan()) },
                    )
                }
            } else results()
        }
        composable(
            Routes.PRODUCT,
            arguments = listOf(
                navArgument("urlKey") { type = NavType.StringType },
                navArgument("bc") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("scanned") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("print") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val source = nav.previousBackStackEntry
            val siblings = source?.savedStateHandle?.get<ArrayList<String>>("productBrowse").orEmpty()
            val index = siblings.indexOf(entry.arguments?.getString("urlKey"))
            val catalog = source?.savedStateHandle?.get<ArrayList<String>>("catalogBrowse").orEmpty()
            val catalogIndex = source?.savedStateHandle?.get<Int>("catalogIndex") ?: -1
            fun adjacent(offset: Int): (() -> Unit)? {
                if (index >= 0) {
                    val key = siblings.getOrNull(index + offset) ?: return null
                    return { if (nav.popBackStack()) nav.navigate(Routes.product(key, null)) }
                }
                if (source == null || catalogIndex < 0) return null
                val query = catalog.getOrNull(catalogIndex + offset) ?: return null
                return {
                    if (nav.popBackStack()) {
                        source.savedStateHandle["catalogIndex"] = catalogIndex + offset
                        nav.navigate(Routes.results(query))
                    }
                }
            }
            ProductScreen(
                onPreviousProduct = adjacent(-1),
                onNextProduct = adjacent(1),
                employee = employee, onBack = { nav.popBackStack() }, onScan = { nav.navigate(Routes.scan()) },
                openPrint = entry.arguments?.getBoolean("print") == true,
            )
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
