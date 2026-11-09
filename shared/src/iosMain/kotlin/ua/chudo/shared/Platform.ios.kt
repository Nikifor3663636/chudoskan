package ua.chudo.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.window.ComposeUIViewController
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeCode128Code
import platform.AVFoundation.AVMetadataObjectTypeEAN13Code
import platform.AVFoundation.AVMetadataObjectTypeEAN8Code
import platform.AVFoundation.AVMetadataObjectTypeUPCECode
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.QuartzCore.CATransaction
import platform.QuartzCore.kCATransactionDisableActions
import platform.UIKit.UIApplication
import platform.UIKit.UIColor
import platform.UIKit.UIDevice
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import platform.AVFoundation.AVCaptureSessionPresetHigh
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import ua.chudo.shared.ui.App

/** Точка входа для Swift: `MainViewControllerKt.MainViewController()`. */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }

actual class KeyValueStore actual constructor() {
    private val d = NSUserDefaults.standardUserDefaults
    actual fun get(key: String): String? = d.stringForKey(key)
    actual fun put(key: String, value: String?) {
        if (value == null) d.removeObjectForKey(key) else d.setObject(value, key)
    }
}

actual fun createHttpClient(): HttpClient = HttpClient(Darwin)

actual fun openUrl(url: String) {
    NSURL.URLWithString(url)?.let { UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any?>(), null) }
}

actual val platformName: String = "iOS " + UIDevice.currentDevice.systemVersion

/** Вид с превью камеры; слой растягиваем при каждом изменении размера. */
@OptIn(ExperimentalForeignApi::class)
private class PreviewView(val session: AVCaptureSession) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    val preview = AVCaptureVideoPreviewLayer(session = session).apply { videoGravity = AVLayerVideoGravityResizeAspectFill }

    init {
        backgroundColor = UIColor.blackColor
        layer.addSublayer(preview)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setValue(true, kCATransactionDisableActions)
        preview.setFrame(bounds)
        CATransaction.commit()
    }
}

private class CodeDelegate(private val onCode: (String) -> Unit) : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {
    private var last: String? = null
    override fun captureOutput(output: AVCaptureOutput, didOutputMetadataObjects: List<*>, fromConnection: AVCaptureConnection) {
        val code = didOutputMetadataObjects.filterIsInstance<AVMetadataMachineReadableCodeObject>()
            .firstNotNullOfOrNull { it.stringValue?.trim()?.takeIf(String::isNotEmpty) } ?: return
        // Тот же код в двух кадрах подряд — защита от случайного misread (как на Android).
        if (code == last) onCode(code)
        last = code
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun BarcodeScannerView(modifier: Modifier, onCode: (String) -> Unit, onUnavailable: (String) -> Unit) {
    val session = remember { AVCaptureSession() }
    val delegate = remember { CodeDelegate { code -> onCode(code) } }
    DisposableEffect(Unit) {
        fun start() {
            val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
            val input = device?.let { AVCaptureDeviceInput.deviceInputWithDevice(it, null) as? AVCaptureDeviceInput }
            if (input == null || !session.canAddInput(input)) { onUnavailable("Камера недоступна"); return }
            session.sessionPreset = AVCaptureSessionPresetHigh
            session.addInput(input)
            val output = AVCaptureMetadataOutput()
            if (!session.canAddOutput(output)) { onUnavailable("Камера недоступна"); return }
            session.addOutput(output)
            output.setMetadataObjectsDelegate(delegate, dispatch_get_main_queue())
            output.metadataObjectTypes = listOf(
                AVMetadataObjectTypeEAN13Code, AVMetadataObjectTypeEAN8Code, AVMetadataObjectTypeUPCECode, AVMetadataObjectTypeCode128Code,
            )
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) { session.startRunning() }
        }
        when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
            AVAuthorizationStatusAuthorized -> start()
            AVAuthorizationStatusNotDetermined -> AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                dispatch_async(dispatch_get_main_queue()) { if (granted) start() else onUnavailable("Дозволь доступ до камери в Налаштуваннях") }
            }
            else -> onUnavailable("Дозволь доступ до камери: Налаштування → ЧудоСкан → Камера")
        }
        onDispose { if (session.running) session.stopRunning() }
    }
    UIKitView(factory = { PreviewView(session) }, modifier = modifier)
}
