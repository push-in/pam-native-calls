package dev.pam.calls

import android.app.Activity
import android.app.Application
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.View
import dev.pam.nativeapp.modules.ModuleCompletion
import dev.pam.nativeapp.protocol.WireValue
import java.lang.ref.WeakReference

/**
 * Picture-in-picture for the in-call screen, driven on the host Activity (the
 * one PHP renders into). The app arms it while a video call shows remote video
 * (`Calls::pictureInPicture(true)`): on Android 12+ leaving the app (Home,
 * Recents, gesture) shrinks the call into a floating window by itself
 * (`setAutoEnterEnabled`); `Calls::enterPictureInPicture()` enters on demand
 * on Android 8+. Mode changes are reported to `Calls::onPictureInPicture()`.
 *
 * The host Activity must declare `android:supportsPictureInPicture="true"`
 * and handle size changes in place (PamActivity does: configChanges
 * screenSize|smallestScreenSize|screenLayout|orientation).
 */
object CallPictureInPicture : Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private var installed = false
    private var host: WeakReference<Activity>? = null
    @Volatile private var eligible = false
    @Volatile private var aspect = Rational(9, 16)
    private var reported: Boolean? = null
    private var pending: Boolean? = null
    private var waiter: ModuleCompletion? = null
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> handler.post(::check) }

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        val application = context.applicationContext as? Application ?: return
        application.registerActivityLifecycleCallbacks(this)
        installed = true
    }

    /** Device and OS support (Android 8+ with the picture-in-picture feature). */
    fun supported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** Whether [activity] declares `android:supportsPictureInPicture` (ActivityInfo.FLAG_SUPPORTS_PICTURE_IN_PICTURE). */
    fun declared(activity: Activity): Boolean = runCatching {
        @Suppress("DEPRECATION")
        val info = activity.packageManager.getActivityInfo(activity.componentName, 0)
        (info.flags and FLAG_SUPPORTS_PICTURE_IN_PICTURE) != 0
    }.getOrDefault(false)

    /** Arms or disarms auto-enter; returns whether the host can enter picture-in-picture. */
    fun configure(context: Context, eligible: Boolean, width: Int, height: Int): Boolean {
        install(context)
        this.eligible = eligible
        aspect = clamp(width, height)
        val activity = host?.get() ?: return false
        if (!supported(activity) || !declared(activity)) return false
        activity.runOnUiThread { applyParams(activity) }
        return true
    }

    /** Enters picture-in-picture now (e.g. a "minimize" button); false when the host cannot. */
    fun enter(context: Context, done: (Boolean) -> Unit) {
        install(context)
        val activity = host?.get()
        if (activity == null || !supported(activity) || !declared(activity)) {
            done(false)
            return
        }
        activity.runOnUiThread {
            done(
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    !activity.isFinishing &&
                    runCatching { activity.enterPictureInPictureMode(params()) }.getOrDefault(false),
            )
        }
    }

    /** Resolves with the next mode change (or the latest unread one). */
    fun next(completion: ModuleCompletion) {
        val ready: Boolean?
        var replaced: ModuleCompletion? = null
        synchronized(this) {
            ready = pending
            if (ready != null) {
                pending = null
            } else {
                replaced = waiter
                waiter = completion
            }
        }
        replaced?.failure("Picture-in-picture read replaced")
        ready?.let { completion.success(mapOf("active" to WireValue.Flag(it))) }
    }

    fun isActive(): Boolean = host?.get()?.isInPictureInPictureMode == true

    /** No call left: auto-enter must not shrink the app after the call. */
    internal fun callsChanged(context: Context) {
        if (!eligible || CallStore.all(context).isNotEmpty()) return
        eligible = false
        host?.get()?.let { activity -> activity.runOnUiThread { applyParams(activity) } }
    }

    private fun applyParams(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.isFinishing || !declared(activity)) return
        runCatching { activity.setPictureInPictureParams(params()) }
    }

    @android.annotation.SuppressLint("NewApi") // only called behind SDK_INT >= O checks
    private fun params(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(aspect)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(eligible)
            builder.setSeamlessResizeEnabled(false)
        }
        return builder.build()
    }

    /** Android accepts aspect ratios between 1:2.39 and 2.39:1 (kept just inside). */
    private fun clamp(width: Int, height: Int): Rational {
        if (width <= 0 || height <= 0) return Rational(9, 16)
        val ratio = width.toDouble() / height
        return when {
            ratio > MAX_RATIO -> Rational(238, 100)
            ratio < 1 / MAX_RATIO -> Rational(100, 238)
            else -> Rational(width, height)
        }
    }

    private fun check() {
        val active = isActive()
        val receiver: ModuleCompletion?
        synchronized(this) {
            val previous = reported
            if (previous == active) return
            reported = active
            // The first observation is the starting state, not a change.
            if (previous == null && !active) return
            receiver = waiter
            if (receiver == null) pending = active else waiter = null
        }
        receiver?.success(mapOf("active" to WireValue.Flag(active)))
    }

    private fun isHost(activity: Activity) = activity !is IncomingCallActivity && activity !is CallActionActivity

    override fun onActivityResumed(activity: Activity) {
        if (!isHost(activity)) return
        if (host?.get() !== activity) {
            host?.get()?.window?.decorView?.removeOnLayoutChangeListener(layoutListener)
            host = WeakReference(activity)
            activity.window?.decorView?.addOnLayoutChangeListener(layoutListener)
            if (eligible) applyParams(activity)
        }
        check()
    }

    override fun onActivityPaused(activity: Activity) {
        if (host?.get() === activity) handler.post(::check)
    }

    override fun onActivityStopped(activity: Activity) {
        if (host?.get() === activity) check()
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (host?.get() !== activity) return
        activity.window?.decorView?.removeOnLayoutChangeListener(layoutListener)
        host = null
        check()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private const val MAX_RATIO = 2.38
    /** Hidden ActivityInfo.FLAG_SUPPORTS_PICTURE_IN_PICTURE (stable since API 24). */
    private const val FLAG_SUPPORTS_PICTURE_IN_PICTURE = 0x400000
}
