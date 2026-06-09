package ua.chudo.scanner.ui.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.ui.common.AiSettingsDialog
import ua.chudo.scanner.ui.common.MessageState
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.max

enum class ScanMode { BARCODE, PHOTO }

@Composable
fun ScannerScreen(
    startMode: ScanMode,
    onBarcode: (String) -> Unit,
    onPhoto: (ByteArray) -> Unit,
    onClose: () -> Unit,
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
            CameraScreen(startMode, onBarcode, onPhoto, onClose)
        } else if (askedOnce) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                MessageState(
                    icon = Icons.Rounded.CameraAlt,
                    title = "Потрібен доступ до камери",
                    text = "Камера читає штрихкод. Фото товару надсилається в ChatGPT лише в режимі «Фото» і лише коли ти натискаєш кнопку.",
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
private fun CameraScreen(
    startMode: ScanMode,
    onBarcode: (String) -> Unit,
    onPhoto: (ByteArray) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = ctx.chudoApp.prefs
    val aiKey by prefs.aiKey.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val currentOnBarcode by rememberUpdatedState(onBarcode)
    val currentOnPhoto by rememberUpdatedState(onPhoto)
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    var mode by rememberSaveable { mutableStateOf(startMode) }
    val currentMode by rememberUpdatedState(mode)
    var camera by remember { mutableStateOf<Camera?>(null) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var torch by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }

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
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { a ->
                a.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null || done || currentMode != ScanMode.BARCODE) {
                        proxy.close(); return@setAnalyzer
                    }
                    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { codes ->
                            val value = codes.firstNotNullOfOrNull { it.rawValue?.trim()?.takeIf(String::isNotEmpty) }
                            if (value != null && value == last && !done && currentMode == ScanMode.BARCODE) {
                                done = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentOnBarcode(value)
                            }
                            last = value
                        }
                        .addOnCompleteListener { proxy.close() }
                }
            }
        val ic = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
        provider.unbindAll()
        camera = runCatching {
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, ic)
                .also { capture = ic }
        }.getOrElse {
            // Слабые камеры не тянут три use case сразу — тогда без фото-режима.
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(ctx).get().unbindAll() }
            scanner.close()
            executor.shutdown()
        }
    }

    fun shoot() {
        if (aiKey == null) { showKey = true; return }
        val ic = capture ?: run {
            Toast.makeText(ctx, "Камера цього телефону не підтримує фото разом зі сканером", Toast.LENGTH_LONG).show()
            return
        }
        busy = true
        val main = ContextCompat.getMainExecutor(ctx)
        ic.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bytes = runCatching { image.toJpegForAi() }.getOrNull()
                image.close()
                main.execute {
                    busy = false
                    if (bytes != null) {
                        done = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnPhoto(bytes)
                    } else {
                        Toast.makeText(ctx, "Не вдалося обробити фото", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                main.execute {
                    busy = false
                    Toast.makeText(ctx, "Не вдалося зробити фото", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        ScanOverlay(Modifier.fillMaxSize(), photo = mode == ScanMode.PHOTO)

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIcon(onClick = onClose) { Icon(Icons.Rounded.Close, "Закрити") }
            Spacer(Modifier.weight(1f))
            if (mode == ScanMode.PHOTO) {
                RoundIcon(onClick = { showKey = true }) { Icon(Icons.Rounded.Key, "Ключ OpenAI") }
                Spacer(Modifier.size(8.dp))
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

        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (mode == ScanMode.BARCODE) "Наведи рамку на штрихкод" else "Сфотографуй упаковку цілком",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                if (mode == ScanMode.BARCODE) "EAN-13 / EAN-8 / UPC з упаковки"
                else if (aiKey == null) "Потрібен ключ OpenAI — натисни кнопку, щоб ввести"
                else "ChatGPT визначить бренд і артикул",
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )

            if (mode == ScanMode.PHOTO) {
                Shutter(busy = busy, onClick = ::shoot)
            }

            SingleChoiceSegmentedButtonRow {
                ScanMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { mode = m },
                        shape = SegmentedButtonDefaults.itemShape(i, ScanMode.entries.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            activeContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            inactiveContainerColor = Color.Black.copy(alpha = 0.45f),
                            inactiveContentColor = Color.White,
                        ),
                    ) { Text(if (m == ScanMode.BARCODE) "Штрихкод" else "Фото товару") }
                }
            }
            FilledTonalButton(onClick = onClose) { Text("Ввести вручну") }
        }
    }

    if (showKey) AiSettingsDialog(prefs) { showKey = false }
}

@Composable
private fun Shutter(busy: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(76.dp)
            .clip(CircleShape)
            .border(4.dp, Color.White, CircleShape)
            .padding(7.dp)
            .clip(CircleShape)
            .background(if (busy) Color.White.copy(alpha = 0.4f) else Color.White)
            .clickable(enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

/** Поворот по EXIF камеры и уменьшение до 1024px: ChatGPT-у больше не нужно, трафик меньше. */
private fun ImageProxy.toJpegForAi(): ByteArray {
    val src = toBitmap()
    val matrix = Matrix().apply {
        postRotate(imageInfo.rotationDegrees.toFloat())
        val scale = MAX_SIDE / max(src.width, src.height).toFloat()
        if (scale < 1f) postScale(scale, scale)
    }
    val bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    return ByteArrayOutputStream().use { out ->
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        out.toByteArray()
    }
}

private const val MAX_SIDE = 1024f

@Composable
private fun RoundIcon(onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Black.copy(alpha = 0.45f),
            contentColor = Color.White,
        ),
    ) { content() }
}

/** Штрихкод: затемнение, уголки и бегущая линия. Фото: только мягкая рамка-подсказка. */
@Composable
private fun ScanOverlay(modifier: Modifier, photo: Boolean) {
    val accent = MaterialTheme.colorScheme.tertiaryContainer
    val t = rememberInfiniteTransition(label = "laser")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "p")

    Canvas(modifier) {
        val w = size.width * 0.82f
        val h = if (photo) w * 1.05f else w * 0.55f
        val left = (size.width - w) / 2
        val top = (size.height - h) / if (photo) 2.6f else 2.3f
        val rect = Rect(Offset(left, top), Size(w, h))
        val r = 24.dp.toPx()
        val hole = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(r))) }

        clipPath(hole, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = if (photo) 0.35f else 0.55f)) }

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
        drawPath(corners, if (photo) Color.White else accent, style = stroke)

        if (!photo) {
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
}
