package ua.chudo.scanner.ui.scan

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ua.chudo.scanner.ui.theme.Mine
import ua.chudo.scanner.R
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.chudo.scanner.chudoApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import ua.chudo.scanner.ui.common.MessageState
import java.util.concurrent.Executors

/**
 * [continuous] — сканувати підряд (кошик): камера не закривається, кожен код віддаємо в [onBarcode];
 * [status] — що вже додано («LEGO · 2 шт»). Той самий код, поки він у кадрі, — один раз.
 */
@Composable
fun ScannerScreen(
    onBarcode: (String) -> Unit,
    onClose: () -> Unit,
    continuous: Boolean = false,
    status: String? = null,
    result: (@Composable () -> Unit)? = null,
    onManual: (() -> Unit)? = null,
    onCartMode: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    priceMode: Boolean = false,
    onPriceMode: ((Boolean) -> Unit)? = null,
    cartUnits: Int? = null,
    acceptingScans: Boolean = true,
) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var askedOnce by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        askedOnce = true
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraSystemBars()
            CameraScreen(onBarcode, onClose, continuous, status, result, onManual, onCartMode, onSearch, priceMode, onPriceMode, cartUnits, acceptingScans)
        } else if (askedOnce) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                MessageState(
                    icon = Icons.Rounded.CameraAlt,
                    title = "Потрібен доступ до камери",
                    text = "Камера потрібна, щоб прочитати штрихкод. Знімки нікуди не надсилаються.",
                    primary = "Дозволити" to { launcher.launch(Manifest.permission.CAMERA) },
                    secondary = "Відкрити налаштування" to {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun CameraSystemBars() {
    val window = LocalActivity.current?.window
    val view = LocalView.current
    val lightBars by rememberUpdatedState(!Mine.colors.dark)
    DisposableEffect(window, view) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            controller?.isAppearanceLightStatusBars = lightBars
            controller?.isAppearanceLightNavigationBars = lightBars
        }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraScreen(
    onBarcode: (String) -> Unit,
    onClose: () -> Unit,
    continuous: Boolean,
    status: String?,
    result: (@Composable () -> Unit)?,
    onManual: (() -> Unit)?,
    onCartMode: (() -> Unit)?,
    onSearch: (() -> Unit)?,
    priceMode: Boolean,
    onPriceMode: ((Boolean) -> Unit)?,
    cartUnits: Int?,
    acceptingScans: Boolean,
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val feedback by ctx.chudoApp.prefs.scanner.collectAsStateWithLifecycle()
    val tone = remember(feedback.sound) {
        if (feedback.sound) runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 45) }.getOrNull() else null
    }
    DisposableEffect(tone) { onDispose { tone?.release() } }
    val canScan by rememberUpdatedState(acceptingScans)
    val onSuccessfulScan by rememberUpdatedState<(String) -> Unit> {
        if (feedback.vibrate) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
        onBarcode(it)
    }
    val previewView = remember { PreviewView(ctx).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
        if (onManual != null) implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    } }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val hasResult = result != null
    LaunchedEffect(hasResult) {
        if (!hasResult) done = false
    }

    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E, Barcode.FORMAT_CODE_128,
                )
                .build()
        )
    }
    val executor = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(Unit) {
        val provider = ProcessCameraProvider.awaitInstance(ctx)
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        // Один и тот же код в двух кадрах подряд — защита от случайного misread.
        var last: String? = null
        // Подряд (кошик): только что добавленный код не считаем снова, пока он не пропадёт из кадра на ~0,8 с.
        var blocked: String? = null
        var blockedSeenAt = 0L
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { a ->
                a.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null || done || !canScan) {
                        proxy.close(); return@setAnalyzer
                    }
                    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { codes ->
                            if (!canScan) return@addOnSuccessListener
                            val value = codes.firstNotNullOfOrNull { it.rawValue?.trim()?.takeIf(String::isNotEmpty) }
                            if (continuous) {
                                val now = System.currentTimeMillis()
                                if (blocked != null && value == blocked) blockedSeenAt = now
                                else if (blocked != null && now - blockedSeenAt > REPEAT_GAP_MS) blocked = null
                                if (value != null && value == last && value != blocked) {
                                    blocked = value
                                    blockedSeenAt = now
                                    onSuccessfulScan(value)
                                }
                            } else if (value != null && value == last && !done) {
                                done = true
                                onSuccessfulScan(value)
                            }
                            last = value
                        }
                        .addOnCompleteListener { proxy.close() }
                }
            }
        provider.unbindAll()
        camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(ctx).get().unbindAll() }
            scanner.close()
            executor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0D0F12))) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        ScannerChrome(
            hit = hasResult,
            onClose = onClose,
            torch = torch,
            onTorch = if (camera?.cameraInfo?.hasFlashUnit() == true) ({
                torch = !torch
                camera?.cameraControl?.enableTorch(torch)
            }) else null,
            status = status,
            result = result,
            onManual = onManual,
            onCartMode = onCartMode,
            onSearch = onSearch,
            priceMode = priceMode,
            onPriceMode = onPriceMode,
            cartUnits = cartUnits,
        )
    }
}

@Composable
internal fun ScannerChrome(
    hit: Boolean,
    onClose: () -> Unit,
    torch: Boolean = false,
    onTorch: (() -> Unit)? = null,
    status: String? = null,
    result: (@Composable () -> Unit)? = null,
    onManual: (() -> Unit)? = null,
    onCartMode: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    priceMode: Boolean = false,
    onPriceMode: ((Boolean) -> Unit)? = null,
    cartUnits: Int? = null,
) {
    Box(Modifier.fillMaxSize()) {
        ScanOverlay(Modifier.fillMaxSize(), hit)
        if (!hit) {
            Layout(content = {
                Text(
                    "Наведіть на штрихкод — скан спрацює сам",
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    color = Color.White.copy(alpha = 0.86f), fontSize = 14.sp, textAlign = TextAlign.Center,
                )
            }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
                val hint = measurables.single().measure(constraints.copy(minHeight = 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    hint.placeRelative(0, (constraints.maxHeight * 0.42f).toInt() + 96.dp.roundToPx())
                }
            }
        }

        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CameraButton(R.drawable.ic_redesign_x, "Закрити", onClose)
                if (onPriceMode != null && onCartMode != null) {
                    Row(
                        Modifier.clip(Mine.fieldShape).background(CameraInk.copy(alpha = 0.6f))
                            .border(1.dp, Color.White.copy(alpha = 0.12f), Mine.fieldShape)
                            .selectableGroup().padding(3.dp),
                    ) {
                        ScanMode("Товар", !priceMode && cartUnits == null) { onPriceMode(false) }
                        ScanMode("У кошик", cartUnits != null, onCartMode)
                        ScanMode("Цінник", priceMode) { onPriceMode(true) }
                    }
                }
                if (onTorch != null) CameraButton(R.drawable.ic_redesign_flashlight, "Ліхтарик", onTorch, torch)
                else Box(Modifier.size(44.dp))
            }
            if (cartUnits != null) {
                val shape = RoundedCornerShape(16.dp)
                Row(
                    Modifier.align(Alignment.CenterHorizontally).background(CameraInk.copy(alpha = 0.72f), shape)
                        .border(1.dp, Color.White.copy(alpha = 0.14f), shape).padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_redesign_shopping_cart), null, Modifier.size(16.dp), tint = Color.White)
                    Text("У кошику $cartUnits шт", color = Color.White, fontSize = 13.sp)
                }
            }
        }

        if (result != null) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) { result() }
        } else if (onManual != null || onSearch != null || cartUnits != null) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (status != null) Text(
                    "Додано: $status", Modifier.padding(bottom = 10.dp),
                    color = Color.White, fontSize = 14.sp, maxLines = 2, textAlign = TextAlign.Center,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (onManual != null) CameraAction("Ввести код", R.drawable.ic_redesign_keyboard, Modifier.weight(1f), onClick = onManual)
                    if (cartUnits != null) CameraAction("Кошик · $cartUnits", null, Modifier.weight(1f), onClose, light = true)
                    else if (onSearch != null) CameraAction("Пошук", R.drawable.ic_redesign_search, Modifier.weight(1f), onClick = onSearch)
                }
            }
        }
    }
}

private val CameraInk = Color(0xFF14161A)

@Composable
private fun ScanMode(label: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Text(
        label,
        Modifier.clip(shape).background(if (active) Color.White else Color.Transparent)
            .selectable(active, role = Role.Tab, onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        color = if (active) Color(0xFF111111) else Color.White.copy(alpha = 0.75f),
        fontSize = 13.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun CameraButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit, active: Boolean? = null) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(44.dp).then(if (active == null) Modifier else Modifier.semantics { selected = active }),
        shape = CircleShape,
        color = if (active == true) Color.White else CameraInk.copy(alpha = 0.6f),
        contentColor = if (active == true) Color(0xFF111111) else Color.White,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), label, Modifier.size(20.dp)) }
    }
}

@Composable
private fun CameraAction(
    title: String,
    @DrawableRes icon: Int?,
    modifier: Modifier,
    onClick: () -> Unit,
    light: Boolean = false,
) {
    Surface(
        onClick = onClick, modifier = modifier.height(44.dp), shape = RoundedCornerShape(6.dp),
        color = if (light) Color.White else CameraInk.copy(alpha = 0.72f),
        contentColor = if (light) Color(0xFF111111) else Color.White,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(painterResource(icon), null, Modifier.size(18.dp))
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Подряд: столько код должен пропасть из кадра, чтобы засчитать его снова (второй такой же товар). */
private const val REPEAT_GAP_MS = 800L

/** Затемнение, уголки и бегущая линия. */
@Composable
private fun ScanOverlay(modifier: Modifier, hit: Boolean) {
    val accent = Color(0xFFFF7500)
    val t = rememberInfiniteTransition(label = "laser")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse), label = "p")

    Canvas(modifier) {
        val w = minOf(280.dp.toPx(), size.width - 32.dp.toPx()).coerceAtLeast(0f)
        val h = w * (150f / 280f)
        val left = (size.width - w) / 2
        val top = size.height * 0.42f - h / 2
        val rect = Rect(Offset(left, top), Size(w, h))
        val r = 10.dp.toPx()
        val hole = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(r))) }

        clipPath(hole, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.42f)) }

        val len = 26.dp.toPx()
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        val corners = Path().apply {
            moveTo(rect.left, rect.top + len); lineTo(rect.left, rect.top + r)
            quadraticTo(rect.left, rect.top, rect.left + r, rect.top); lineTo(rect.left + len, rect.top)
            moveTo(rect.right - len, rect.top); lineTo(rect.right - r, rect.top)
            quadraticTo(rect.right, rect.top, rect.right, rect.top + r); lineTo(rect.right, rect.top + len)
            moveTo(rect.right, rect.bottom - len); lineTo(rect.right, rect.bottom - r)
            quadraticTo(rect.right, rect.bottom, rect.right - r, rect.bottom); lineTo(rect.right - len, rect.bottom)
            moveTo(rect.left + len, rect.bottom); lineTo(rect.left + r, rect.bottom)
            quadraticTo(rect.left, rect.bottom, rect.left, rect.bottom - r); lineTo(rect.left, rect.bottom - len)
        }
        drawPath(corners, if (hit) Color(0xFF5AD08D) else Color.White, style = stroke)

        if (!hit) {
            val y = rect.center.y + (2f * p - 1f) * minOf(52.dp.toPx(), h / 3)
            val x = rect.left + 14.dp.toPx()
            val lineWidth = (rect.width - 28.dp.toPx()).coerceAtLeast(0f)
            val glow = 6.dp.toPx()
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.25f), Color.Transparent), y - glow, y + glow),
                topLeft = Offset(x, y - glow), size = Size(lineWidth, 2 * glow),
            )
            drawLine(accent.copy(alpha = 0.9f), Offset(x, y), Offset(x + lineWidth, y), 2.dp.toPx())
        }
    }
}
