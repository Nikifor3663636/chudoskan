package ua.chudo.scanner.ui.scan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ua.chudo.scanner.ui.theme.Mine
import ua.chudo.scanner.ui.theme.MineDark
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
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
            CameraScreen(onBarcode, onClose, continuous, status, result, onManual, onCartMode)
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
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val currentOnBarcode by rememberUpdatedState(onBarcode)
    val previewView = remember { PreviewView(ctx).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
        if (onManual != null) implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    } }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }

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
                    if (media == null || done) {
                        proxy.close(); return@setAnalyzer
                    }
                    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { codes ->
                            val value = codes.firstNotNullOfOrNull { it.rawValue?.trim()?.takeIf(String::isNotEmpty) }
                            if (continuous) {
                                val now = System.currentTimeMillis()
                                if (blocked != null && value == blocked) blockedSeenAt = now
                                else if (blocked != null && now - blockedSeenAt > REPEAT_GAP_MS) blocked = null
                                if (value != null && value == last && value != blocked) {
                                    blocked = value
                                    blockedSeenAt = now
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    currentOnBarcode(value)
                                }
                            } else if (value != null && value == last && !done) {
                                done = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentOnBarcode(value)
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

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        ScanOverlay(Modifier.fillMaxSize())

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIcon(onClick = onClose) { Icon(Icons.Rounded.Close, "Закрити") }
            Spacer(Modifier.weight(1f))
            onCartMode?.let { open ->
                androidx.compose.material3.TextButton(onClick = open) { Text("У кошик", color = Color.White) }
            }
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                RoundIcon(onClick = {
                    torch = !torch
                    camera?.cameraControl?.enableTorch(torch)
                }) {
                    Icon(if (torch) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff, "Ліхтарик")
                }
            }
        }

        if (result != null) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp)) { result() }
        } else Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (continuous && onManual == null) "Скануй товари покупця" else "Наведи рамку на штрихкод",
                color = MineDark.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Text(
                if (continuous && onManual == null) status?.let { "Додано: $it" } ?: "Кожен код додається в кошик; ще один такий самий — прибери й наведи знову"
                else if (onManual != null) "Після сканування тут будуть ціна й залишок" else "Телефон сам прочитає код",
                Modifier.padding(top = 6.dp),
                color = if (continuous && status != null) MineDark.accent else MineDark.textSecondary,
                fontSize = 15.sp, fontWeight = if (continuous && status != null) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center, maxLines = 2,
            )
            if (onManual != null || continuous) {
                Surface(
                    onClick = onManual ?: onClose, modifier = Modifier.padding(top = 20.dp).fillMaxWidth().height(52.dp),
                    shape = Mine.pillShape,
                    color = if (continuous) MineDark.accent else MineDark.card.copy(alpha = 0.92f),
                    contentColor = if (continuous) MineDark.onAccent else MineDark.text,
                    border = if (continuous) null else BorderStroke(1.dp, MineDark.outline),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(if (continuous && onManual == null) "Готово — до кошика" else "Ввести код вручну", fontSize = 16.sp, fontWeight = if (continuous) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/** Подряд: столько код должен пропасть из кадра, чтобы засчитать его снова (второй такой же товар). */
private const val REPEAT_GAP_MS = 800L

/** Круглая кнопка 48 dp поверх камеры. */
@Composable
private fun RoundIcon(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick, modifier = Modifier.size(48.dp), shape = CircleShape,
        color = MineDark.card.copy(alpha = 0.85f), contentColor = MineDark.text,
    ) { Box(contentAlignment = Alignment.Center) { content() } }
}

/** Затемнение, уголки и бегущая линия. */
@Composable
private fun ScanOverlay(modifier: Modifier) {
    // Рамка — фирменным оранжевым (как кнопка сканера).
    val accent = MineDark.accent
    val t = rememberInfiniteTransition(label = "laser")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "p")

    Canvas(modifier) {
        val w = size.width * 0.82f
        val h = w * 0.55f
        val left = (size.width - w) / 2
        val top = (size.height - h) / 2.3f
        val rect = Rect(Offset(left, top), Size(w, h))
        val r = 24.dp.toPx()
        val hole = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(r))) }

        clipPath(hole, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.55f)) }

        val len = 34.dp.toPx()
        val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
        val corners = Path().apply {
            moveTo(rect.left, rect.top + len); lineTo(rect.left, rect.top + r / 2)
            quadraticTo(rect.left, rect.top, rect.left + r / 2, rect.top); lineTo(rect.left + len, rect.top)
            moveTo(rect.right - len, rect.top); lineTo(rect.right - r / 2, rect.top)
            quadraticTo(rect.right, rect.top, rect.right, rect.top + r / 2); lineTo(rect.right, rect.top + len)
            moveTo(rect.right, rect.bottom - len); lineTo(rect.right, rect.bottom - r / 2)
            quadraticTo(rect.right, rect.bottom, rect.right - r / 2, rect.bottom); lineTo(rect.right - len, rect.bottom)
            moveTo(rect.left + len, rect.bottom); lineTo(rect.left + r / 2, rect.bottom)
            quadraticTo(rect.left, rect.bottom, rect.left, rect.bottom - r / 2); lineTo(rect.left, rect.bottom - len)
        }
        drawPath(corners, accent, style = stroke)

        val y = rect.top + 14.dp.toPx() + (rect.height - 28.dp.toPx()) * p
        drawLine(
            accent.copy(alpha = 0.9f),
            Offset(rect.left + 18.dp.toPx(), y),
            Offset(rect.right - 18.dp.toPx(), y),
            strokeWidth = 2.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}
