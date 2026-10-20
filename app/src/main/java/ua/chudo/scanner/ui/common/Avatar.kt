package ua.chudo.scanner.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.AuthState

fun initials(name: String): String =
    name.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

/** Кружок с инициалами сотрудника; по нажатию — профиль. */
@Composable
fun Avatar(name: String, size: Dp = 36.dp, onClick: (() -> Unit)? = null) {
    val label = "Профіль"
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials(name),
            style = if (size >= 56.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** Аватар текущего сотрудника (для шапок экранов). */
@Composable
fun MyAvatar(onClick: () -> Unit) {
    val state by LocalContext.current.chudoApp.auth.state.collectAsStateWithLifecycle()
    val name = (state as? AuthState.Ready)?.employee?.name.orEmpty()
    Avatar(name, onClick = onClick)
}
