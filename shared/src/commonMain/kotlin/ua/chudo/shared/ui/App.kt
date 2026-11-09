package ua.chudo.shared.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import ua.chudo.shared.KeyValueStore
import ua.chudo.shared.auth.AuthRepository
import ua.chudo.shared.auth.AuthState
import ua.chudo.shared.auth.Employee
import ua.chudo.shared.createHttpClient
import ua.chudo.shared.data.ChudoRepository
import ua.chudo.shared.data.Prefs
import ua.chudo.shared.data.SiteClient

/** Всё приложение — один контейнер (как ChudoApp на Android). */
class AppGraph {
    val kv = KeyValueStore()
    val http = createHttpClient()
    val prefs = Prefs(kv)
    val repo = ChudoRepository(SiteClient(http), prefs)
    val auth = AuthRepository(http, kv)
}

val LocalGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph не задано") }

/** Экраны приложения; навигация — простой стек (одинаково на Android и iOS). */
sealed interface Screen {
    data object Home : Screen
    data object Scan : Screen
    data class Results(val query: String) : Screen
    data class Product(val urlKey: String, val barcode: String?) : Screen
    data object Profile : Screen
}

class Navigator(start: Screen) {
    val stack = mutableStateListOf(start)
    val current get() = stack.last()
    fun go(s: Screen) { stack.add(s) }
    /** Заменить верхний экран (результаты → карточка товара). */
    fun replace(s: Screen) { stack[stack.lastIndex] = s }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
}

/** Точка входа общего UI: iOS — MainViewController, Android — setContent { App() }. */
@Composable
fun App() {
    val graph = remember { AppGraph() }
    setSingletonImageLoaderFactory { ctx ->
        ImageLoader.Builder(ctx).components { add(KtorNetworkFetcherFactory(httpClient = { graph.http })) }.build()
    }
    ChudoTheme {
        CompositionLocalProvider(LocalGraph provides graph) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val state by graph.auth.state.collectAsStateWithLifecycle()
                when (val s = state) {
                    is AuthState.Ready -> MainScreens(s.employee)
                    else -> AuthScreen(s)
                }
            }
        }
    }
}

@Composable
private fun MainScreens(employee: Employee) {
    val graph = LocalGraph.current
    val nav = remember(employee.uid) { Navigator(Screen.Home) }
    // Поиск и наличие сразу в городе сотрудника.
    LaunchedEffect(employee.uid) {
        if (employee.region.isNotEmpty() && graph.prefs.region.value.code != employee.region) graph.prefs.setRegion(employee.region)
    }
    AnimatedContent(nav.current, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "nav") { screen ->
        when (screen) {
            Screen.Home -> HomeScreen(nav)
            Screen.Scan -> ScanScreen(nav)
            is Screen.Results -> ResultsScreen(screen.query, nav)
            is Screen.Product -> ProductScreen(screen.urlKey, screen.barcode, nav)
            Screen.Profile -> ProfileScreen(employee, nav)
        }
    }
}
