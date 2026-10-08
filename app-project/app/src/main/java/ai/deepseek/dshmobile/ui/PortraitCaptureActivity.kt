package ai.deepseek.dshmobile.ui

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * The QR capture screen, held in portrait.
 *
 * This subclass exists only to give the capture screen a portrait
 * `screenOrientation` in *our* manifest. The library declares its own
 * `CaptureActivity` as `sensorLandscape`, which cannot be changed from here
 * without a `tools:replace` fight with the manifest merger, so the app points
 * `ScanOptions` at this class instead and declares it below. Nothing is
 * overridden: the inherited camera preview, viewfinder and result handling are
 * exactly the library's.
 *
 * Why portrait rather than the landscape the 1.1.4 comment argued for: the
 * capture screen is reached by holding the phone the way a phone is normally
 * held, and a scanner that rotates the device out from under the user's thumb
 * is the thing users complain about. The pairing QR is square, so a portrait
 * viewfinder frames it just as well — what the wide desktop screen changes is
 * the *size* of the code, not its shape.
 *
 * See `QrScan.kt` for why orientation locking is left off and the manifest is
 * the single source of truth.
 */
class PortraitCaptureActivity : CaptureActivity()
