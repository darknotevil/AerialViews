package com.neilturner.aerialviews.ui.core

import android.media.MediaPlayer
import android.os.SystemClock
import android.view.SurfaceHolder
import timber.log.Timber
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max

/**
 * Hardware image path for the MStar/MediaTek chipsets used by Xiaomi and Redmi TVs.
 *
 * On those sets the Android display is locked to a single 1920x1080 mode while the panel is
 * 4K, so everything the UI draws is upscaled by the panel. The chipset offers a second route:
 * com.mstar.android.media.MMediaPlayer, a MediaPlayer subclass that hands a JPEG to the
 * hardware decoder and puts it on the video plane at the panel's own resolution, bypassing
 * the 1080p framebuffer.
 *
 * Xiaomi's gallery reaches this through mitv.graphics.ImagePlayerFor4KManager, but that
 * wrapper eagerly builds MEMCDisplay, which binds the vendor.mediatek.tv.mtktvfactory HAL -
 * out of reach for a normal app, which fails with NoSuchElementException from HwBinder.
 * Talking to MMediaPlayer directly skips the wrapper and works from an ordinary app.
 *
 * Everything is reflective: these classes only exist in the com.mstar.android shared library
 * on such devices, so [isAvailable] is false everywhere else and callers fall back to Coil.
 */
class MStarImagePlayer {
    private var player: MediaPlayer? = null

    /**
     * Decodes [path] and puts it on the video plane, replacing whatever the previous call put
     * there. Blocks until the chipset reports the frame is up, so call it off the main thread.
     *
     * [rotationDegrees] is the EXIF orientation (0/90/180/270): the hardware path does not read
     * EXIF, so it is applied explicitly the way Xiaomi's gallery does. [fillPlane] picks the
     * scale mode: false fits the whole image inside the plane, true fills the plane and crops.
     *
     * Returns false if the platform refused any step; the caller should fall back to the
     * normal software path and not call [show] again for this image.
     */
    fun show(
        holder: SurfaceHolder,
        path: String,
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int = 0,
        fillPlane: Boolean = false,
    ): Boolean {
        if (mediaPlayerClass == null || initParameterClass == null) return false
        val tuning = PlaneTuning.load()

        // The decoder can be told to drop resolution while decoding (DCT-domain 1/2, 1/4, 1/8),
        // which is what Xiaomi's gallery does for anything bigger than the plane - and that
        // halves a 4000 px photo to 2000 px before it is scaled back up. Verified on device
        // that the chipset decodes an oversized JPEG at full size and fits it to the plane on
        // its own, so full decode is tried first and the sampled decode is the fallback.
        val sampled = sampleSizeFor(imageWidth, imageHeight).first
        val attempts = if (tuning.sample != null) listOf(tuning.sample) else listOf(1, sampled).distinct()
        for (sampleSize in attempts) {
            if (attempt(holder, path, imageWidth, imageHeight, rotationDegrees, fillPlane, sampleSize, tuning)) {
                return true
            }
            Timber.w("MStar: sample $sampleSize failed for $path")
        }
        return false
    }

    private fun attempt(
        holder: SurfaceHolder,
        path: String,
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int,
        fillPlane: Boolean,
        sampleSize: Int,
        tuning: PlaneTuning,
    ): Boolean {
        val playerClass = mediaPlayerClass ?: return false
        val initClass = initParameterClass ?: return false

        return try {
            val instance =
                player?.also { playerClass.getMethod("reset").invoke(it) }
                    ?: (playerClass.newInstance() as MediaPlayer).also { fresh ->
                        playerClass.getMethod("reset").invoke(fresh)
                        player = fresh
                    }

            playerClass.getMethod("setDisplay", SurfaceHolder::class.java).invoke(instance, holder)
            playerClass.getMethod("setDataSource", String::class.java).invoke(instance, path)

            val parameters = newInitParameter(initClass, playerClass, instance, sampleSizeFor(imageWidth, imageHeight).second)
            val sized =
                playerClass
                    .getMethod(
                        "SetImageSampleSize",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        initClass,
                    ).invoke(instance, sampleSize, PLANE_WIDTH, PLANE_HEIGHT, parameters)
            if (sized == false) {
                Timber.w("MStar: SetImageSampleSize refused $imageWidth x $imageHeight sample=$sampleSize")
                return false
            }

            // The chipset signals a decoded frame through onInfo(3, 0) rather than through
            // prepare()/start() returning, so wait for it before reporting success.
            val shown = CountDownLatch(1)
            var failed = false
            instance.setOnInfoListener { _, what, extra ->
                if (what == INFO_FRAME_READY && extra == 0) shown.countDown()
                true
            }
            instance.setOnErrorListener { _, what, extra ->
                Timber.w("MStar: player error what=$what extra=$extra")
                failed = true
                shown.countDown()
                true
            }

            val started = SystemClock.uptimeMillis()
            playerClass.getMethod("prepare").invoke(instance)
            playerClass.getMethod("start").invoke(instance)

            if (!shown.await(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Timber.w("MStar: no frame within ${FRAME_TIMEOUT_MS}ms for $path")
                return false
            }
            if (failed) return false
            Timber.i("MStar: ${imageWidth}x$imageHeight sample=$sampleSize decoded in ${SystemClock.uptimeMillis() - started}ms")

            // Rotation and scale are only accepted once a frame is up (the InitParameter scale
            // is ignored by the chipset; verified on device). ImageRotate takes degrees: the
            // 0/2/4/6 codes Xiaomi's gallery passes tilt the frame by 2-6 degrees here.
            //
            // The chipset fits an oversized decode to the plane on its own (shrink only) before
            // any rotation, and a rotation keeps that size, so work out the on-plane size from
            // the fitted, then rotated, decode and scale that to the plane.
            val decodedWidth = imageWidth / sampleSize
            val decodedHeight = imageHeight / sampleSize
            val nativeFit = minOf(1f, PLANE_WIDTH.toFloat() / decodedWidth, PLANE_HEIGHT.toFloat() / decodedHeight)
            val upright = rotationDegrees == 90 || rotationDegrees == 270
            val shownWidth = (if (upright) decodedHeight else decodedWidth) * nativeFit
            val shownHeight = (if (upright) decodedWidth else decodedHeight) * nativeFit
            val fitX = PLANE_WIDTH / shownWidth
            val fitY = PLANE_HEIGHT / shownHeight
            val factor = tuning.postScale ?: if (fillPlane) maxOf(fitX, fitY) else minOf(fitX, fitY)

            // Each ImageRotate / ImageScale call re-renders the decode with only its own
            // transform (a scale after a rotate undoes the rotate), so both go in one call.
            if (rotationDegrees != 0) {
                val result =
                    playerClass
                        .getMethod(
                            "ImageRotateAndScale",
                            Float::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                        ).invoke(instance, rotationDegrees.toFloat(), factor, factor, tuning.postScaleFlag)
                Timber.i("MStar: ImageRotateAndScale($rotationDegrees, $factor) -> $result")
            } else if (factor > 1.001f) {
                val result =
                    playerClass
                        .getMethod(
                            "ImageScale",
                            Float::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                        ).invoke(instance, factor, factor, tuning.postScaleFlag)
                Timber.i("MStar: ImageScale($factor, crop=${tuning.postScaleFlag}) -> $result")
            }
            true
        } catch (ex: Throwable) {
            // Throwable, not Exception: a missing or mismatched platform class surfaces as
            // LinkageError (NoSuchFieldError, UnsatisfiedLinkError), which is an Error.
            Timber.w(ex, "MStar: hardware image path failed for $path")
            release()
            false
        }
    }

    fun release() {
        val instance = player ?: return
        player = null
        try {
            instance.release()
        } catch (ex: Throwable) {
            Timber.w(ex, "MStar: release failed")
        }
    }

    private fun newInitParameter(
        initClass: Class<*>,
        playerClass: Class<*>,
        instance: Any,
        scale: Float,
    ): Any {
        // InitParameter is a non-static inner class, so its constructor takes the player.
        val constructor = initClass.getDeclaredConstructor(playerClass)
        constructor.isAccessible = true
        val parameters = constructor.newInstance(instance)
        setField(initClass, parameters, "degrees", 0f)
        setField(initClass, parameters, "scaleX", scale)
        setField(initClass, parameters, "scaleY", scale)
        setField(initClass, parameters, "cropX", 0)
        setField(initClass, parameters, "cropY", 0)
        setField(initClass, parameters, "cropWidth", 0)
        setField(initClass, parameters, "cropHeight", 0)
        return parameters
    }

    private fun setField(
        owner: Class<*>,
        target: Any,
        name: String,
        value: Any,
    ) {
        val field = owner.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    /**
     * The decoder only accepts power-of-two sample sizes and its output has to fit the plane,
     * so an image larger than the plane is decoded at the next size up and the remainder is
     * made up by the scale factor, which the chipset applies after decoding. Mirrors what
     * ImagePlayerFor4KManagerImpl.setSampleSurfaceSize does for images at least plane-sized.
     *
     * Xiaomi never sends anything smaller than the plane, so its `< 1` branch (scale 1, i.e.
     * the image sits unscaled inside the plane) is untested there; here a smaller image is
     * scaled up to fill the plane instead, the same way a larger one is scaled after sampling.
     */
    private fun sampleSizeFor(
        imageWidth: Int,
        imageHeight: Int,
    ): Pair<Int, Float> {
        val ratio =
            max(
                imageWidth.toDouble() / PLANE_WIDTH,
                imageHeight.toDouble() / PLANE_HEIGHT,
            )
        return when {
            ratio == 1.0 || ratio == 2.0 || ratio == 4.0 || ratio == 8.0 -> ratio.toInt() to 1f
            ratio < 1.0 -> 1 to (1 / ratio).toFloat()
            ratio < 2.0 -> ceil(ratio).toInt().let { it to (it / ratio).toFloat() }
            ratio < 4.0 -> 2 to (2 / ratio).toFloat()
            ratio < 8.0 -> 4 to (4 / ratio).toFloat()
            else -> 8 to (8 / ratio).toFloat()
        }
    }

    /**
     * Developer overrides for experimenting with the decoder on a live TV without rebuilding:
     * a properties file at [PlaneTuning.PATH] with any of `sample` (force a decode sample
     * size), `post_scale` (force the scale factor applied after the frame is up),
     * `post_scale_flag` (the boolean ImageScale takes), `min_gate=0` (send every local JPEG to
     * the plane regardless of size). Absent file = no overrides. Re-read on every image.
     */
    class PlaneTuning private constructor(
        val sample: Int?,
        val postScale: Float?,
        val postScaleFlag: Boolean,
        val minGate: Int?,
    ) {
        companion object {
            const val PATH = "/sdcard/Download/mstar_plane_test.properties"
            private val NONE = PlaneTuning(null, null, false, null)

            fun load(): PlaneTuning {
                val file = java.io.File(PATH)
                if (!file.canRead()) return NONE
                return try {
                    val props = java.util.Properties().also { p -> file.inputStream().use { p.load(it) } }
                    PlaneTuning(
                        sample = props.getProperty("sample")?.trim()?.toIntOrNull(),
                        postScale = props.getProperty("post_scale")?.trim()?.toFloatOrNull(),
                        postScaleFlag = props.getProperty("post_scale_flag")?.trim() == "true",
                        minGate = props.getProperty("min_gate")?.trim()?.toIntOrNull(),
                    )
                } catch (ex: Exception) {
                    Timber.w(ex, "MStar: tuning file unreadable")
                    NONE
                }
            }
        }
    }

    companion object {
        private const val PLANE_WIDTH = 3840
        private const val PLANE_HEIGHT = 2160
        private const val INFO_FRAME_READY = 3
        private const val FRAME_TIMEOUT_MS = 4000L

        private var exemptionAttempted = false

        /**
         * MMediaPlayer's static initialiser calls native_init(), which looks up
         * android.media.MediaPlayer#mNativeContext over JNI. That field is on the dark
         * greylist, so the platform denies it and class initialisation dies with
         * NoSuchFieldError. Asking the runtime to exempt the class lifts that.
         *
         * Must run before the class is loaded at all - Class.forName(String) initialises it,
         * so by the time anything else runs it is already too late.
         *
         * The double reflection is required: reaching VMRuntime through plain reflection is
         * itself blocked, while calling Class#getDeclaredMethod reflectively is not, since the
         * caller then appears to be the platform rather than this app.
         */
        private fun exemptMediaPlayerFromHiddenApi() {
            if (exemptionAttempted) return
            exemptionAttempted = true
            try {
                val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
                val getDeclaredMethod =
                    Class::class.java.getDeclaredMethod(
                        "getDeclaredMethod",
                        String::class.java,
                        arrayOf<Class<*>>()::class.java,
                    )
                val vmRuntime = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
                val getRuntime =
                    getDeclaredMethod.invoke(vmRuntime, "getRuntime", arrayOf<Class<*>>()) as Method
                val setExemptions =
                    getDeclaredMethod.invoke(
                        vmRuntime,
                        "setHiddenApiExemptions",
                        arrayOf<Class<*>>(Array<String>::class.java),
                    ) as Method
                setExemptions.invoke(getRuntime.invoke(null), arrayOf("Landroid/media/MediaPlayer;"))
                Timber.i("MStar: hidden API exemption applied")
            } catch (ex: Throwable) {
                Timber.w(ex, "MStar: could not apply hidden API exemption")
            }
        }

        private val mediaPlayerClass: Class<*>? by lazy {
            exemptMediaPlayerFromHiddenApi()
            try {
                Class.forName("com.mstar.android.media.MMediaPlayer")
            } catch (ex: Throwable) {
                Timber.w(ex, "MStar: MMediaPlayer unavailable")
                null
            }
        }

        private val initParameterClass: Class<*>? by lazy {
            try {
                Class.forName("com.mstar.android.media.MMediaPlayer\$InitParameter")
            } catch (_: Throwable) {
                null
            }
        }

        val isAvailable: Boolean
            get() = mediaPlayerClass != null && initParameterClass != null

        @Volatile
        private var holdUntilUptime = 0L

        /**
         * Keeps images off the video plane until the given SystemClock.uptimeMillis().
         * The plane is only visible through a SurfaceView hole in the UI, so a caller that
         * knows another window is still composited underneath (see MiTvScreensaverActivity)
         * can ask for the software path in the meantime.
         */
        fun holdVideoPlaneUntil(uptimeMillis: Long) {
            holdUntilUptime = maxOf(holdUntilUptime, uptimeMillis)
        }

        val isVideoPlaneHeld: Boolean
            get() = SystemClock.uptimeMillis() < holdUntilUptime
    }
}
