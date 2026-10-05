package dev.pam.calls

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import java.lang.ref.WeakReference

/**
 * Keeps the application's activities visible over the lock screen (and turns
 * the screen on) while a call was accepted or is ongoing, so the in-call
 * screen rendered by PHP is reachable without unlocking — like a phone app.
 */
object LockScreenPolicy : Application.ActivityLifecycleCallbacks {
    private var installed = false
    private var current: WeakReference<Activity>? = null
    private var appContext: Context? = null

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        val application = context.applicationContext as? Application ?: return
        appContext = application
        application.registerActivityLifecycleCallbacks(this)
        installed = true
    }

    fun refresh(context: Context) {
        install(context)
        val activity = current?.get() ?: return
        activity.runOnUiThread { apply(activity) }
    }

    fun isActive(context: Context): Boolean = CallStore.hasActiveCall(context)

    private fun apply(activity: Activity) {
        if (activity is IncomingCallActivity) return
        val show = CallStore.hasActiveCall(activity)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(show)
            activity.setTurnScreenOn(show)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (show) activity.window.addFlags(flags) else activity.window.clearFlags(flags)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = apply(activity)

    override fun onActivityResumed(activity: Activity) {
        current = WeakReference(activity)
        apply(activity)
    }

    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (current?.get() === activity) current = null
    }
}

/** Installs [LockScreenPolicy] at process start, before any activity is created. */
class CallsInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let(LockScreenPolicy::install)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
