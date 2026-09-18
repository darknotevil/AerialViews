package com.neilturner.aerialviews.ui.screensaver

import android.app.Activity
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.neilturner.aerialviews.ui.core.MStarImagePlayer
import timber.log.Timber

/**
 * Entry point used by MIUI TV (Xiaomi/Redmi, Android 9) to start the screensaver.
 *
 * The stock screensaver app fires com.mitv.gallery.action.SHOW_SCREENSAVER from
 * com.mitv.screensaver/RenderActivity with a bare intent - no FLAG_ACTIVITY_NEW_TASK,
 * no extras. Without a launch mode of its own the activity would be placed into the
 * caller's task, and the finishAndRemoveTask() call in TestActivity.onStop() would
 * then tear that whole task down, taking the app the user was watching with it.
 *
 * singleInstance plus a dedicated task affinity keeps the screensaver in a task of
 * its own regardless of how the caller launched it, and makes a repeated trigger
 * reuse the running instance instead of stacking a second copy on top.
 *
 * RenderActivity never gets told that the screensaver ended. It relies on a trick
 * instead: right after handing over it posts its own finish() with a 5 s delay, and
 * its onStop() cancels that post. The stock gallery screensaver is a translucent
 * activity, so RenderActivity stays paused-but-visible underneath it, the delayed
 * finish() runs, and by the time the gallery exits there is nothing left below it.
 * An opaque activity stops RenderActivity at once, the finish() is cancelled, and
 * pressing Back lands on RenderActivity's empty grey window.
 *
 * So this activity uses a translucent theme (with a black window background so nothing
 * shows through), then switches itself to opaque once RenderActivity has had time to
 * finish. Opaque matters: under a translucent activity the app the user came from
 * is only paused, never stopped, for the whole screensaver.
 *
 * Behaviour is otherwise identical to TestActivity.
 */
class MiTvScreensaverActivity : TestActivity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A SurfaceView hole would show RenderActivity's grey window instead of the video
        // plane while it is still underneath, so keep images on the software path until then.
        MStarImagePlayer.holdVideoPlaneUntil(SystemClock.uptimeMillis() + OPAQUE_DELAY_MS + FORMAT_RESET_DELAY_MS)
        handler.postDelayed(::convertToOpaque, OPAQUE_DELAY_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun convertToOpaque() {
        if (isFinishing) return
        try {
            // Hidden API, but on the greylist for Android 9 (the only platform that reaches
            // this activity) and public as Activity.setTranslucent(false) from Android 11.
            Activity::class.java.getMethod("convertFromTranslucent").invoke(this)
            Timber.i("MIUI TV screensaver: converted to opaque")
        } catch (ex: Throwable) {
            Timber.w(ex, "MIUI TV screensaver: could not convert to opaque")
            return
        }
        // convertFromTranslucent() also flags the window's surface as opaque in SurfaceFlinger,
        // and the window manager only re-evaluates that flag on a pixel-format change. Left
        // alone, the flag makes SurfaceFlinger cull everything below the window - including
        // the SurfaceView hole the MStar video plane shows through, which turns 4K images
        // black. So force a format change. It has to be a format with alpha: as soon as any
        // SurfaceView is in the hierarchy (the video player's, for one) ViewRootImpl rewrites
        // an alpha-less format back to TRANSLUCENT before it reaches the window manager, so
        // OPAQUE would never register. The surface itself is always TRANSLUCENT for a
        // hardware-accelerated window, so the change is applied in place and only the
        // opaque flag is recomputed - from the new format, i.e. back to non-opaque.
        val window = window ?: return
        val format =
            if (window.attributes.format == PixelFormat.RGBA_8888) {
                PixelFormat.TRANSLUCENT
            } else {
                PixelFormat.RGBA_8888
            }
        window.setFormat(format)
        Timber.i("MIUI TV screensaver: window format bumped to $format, video plane usable")
    }

    companion object {
        /**
         * RenderActivity posts its finish() 5 s after its onPause(), which happens just before
         * this activity is created. The extra margin covers a busy main thread over there.
         */
        private const val OPAQUE_DELAY_MS = 7_500L

        /** Margin for the format change above to reach the window manager. */
        private const val FORMAT_RESET_DELAY_MS = 500L
    }
}
