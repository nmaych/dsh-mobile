package ai.deepseek.dshmobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Scan a QR code and hand back its text.
 *
 * This exists so connecting to `dsh-mobile-connect` is one action instead of
 * three. The desktop already prints a QR carrying
 * `dshmobile://pair?host=…&port=…&code=…`, and the app already knows how to
 * follow that link — `MainActivity` has handled the `dshmobile://pair` scheme
 * since 1.1.3, so a phone with a *separate* camera app could always scan it and
 * let Android route the intent back. What was missing is the case that actually
 * happens: the QR is on the computer screen in front of the user, and reaching
 * for a second device to read it is absurd. So the app scans it itself.
 *
 * @return a `launch` function; call it from a click handler.
 *
 * No CAMERA permission handling appears here on purpose. The bundled
 * `CaptureActivity` requests it itself (`CaptureManager` checks with
 * `ContextCompat.checkSelfPermission` and asks through
 * `ActivityCompat.requestPermissions`), and shows its own rationale. Asking
 * again here would double-prompt and, worse, could show a denial message for a
 * permission the capture screen was about to request anyway.
 */
@Composable
fun rememberQrScanner(onResult: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        // `contents` is null when the user backs out without scanning, which is a
        // cancel rather than an error and must stay silent.
        result.contents?.trim()?.takeIf { it.isNotEmpty() }?.let(onResult)
    }
    return {
        launcher.launch(
            ScanOptions()
                // One format only. A QR is what the desktop prints, and narrowing
                // it stops the decoder latching onto a barcode on a box behind the
                // screen.
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("把电脑上的配对二维码放进框内")
                // Silent: the beep is the single most-complained-about part of a
                // scanner, and the result here is obvious from the screen changing.
                .setBeepEnabled(false)
                // Portrait, via our own subclass. The library's `CaptureActivity`
                // is declared `sensorLandscape` in *its* manifest, and that entry
                // cannot be changed from this module, so the direction is set on
                // `PortraitCaptureActivity` instead.
                .setCaptureActivity(PortraitCaptureActivity::class.java)
                // Left unlocked, so the manifest is the ONE place the direction is
                // declared. With the library default of `true`, `CaptureManager`
                // also calls `setRequestedOrientation` from whatever orientation
                // it reads at creation — a second source of truth that can only
                // ever agree or surprise. The capture screen is reached by holding
                // the phone normally, and the pairing QR is square, so a portrait
                // viewfinder frames it as well as a wide one.
                .setOrientationLocked(false)
        )
    }
}
