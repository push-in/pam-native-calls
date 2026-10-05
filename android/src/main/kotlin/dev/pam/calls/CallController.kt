package dev.pam.calls

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import dev.pam.nativeapp.modules.ModuleCompletion
import dev.pam.nativeapp.protocol.WireValue
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Single entry point for every call transition, whether it comes from PHP, a
 * notification action, the lock-screen UI, a timeout or a push message.
 */
object CallController {
    const val KIND_ACCEPT = 1
    const val KIND_DECLINE = 2
    const val KIND_OPEN = 3
    const val KIND_HANG_UP = 4
    const val KIND_TIMEOUT = 5

    private val handler = Handler(Looper.getMainLooper())
    internal val io = Executors.newCachedThreadPool { Thread(it, "PamCalls").apply { isDaemon = true } }
    private var waiter: ModuleCompletion? = null
    private val screens = CopyOnWriteArraySet<WeakReference<Activity>>()

    // region incoming / ongoing

    /** Posts the ringing surfaces. Blocking (avatar download); run off the main thread. */
    fun showIncoming(context: Context, call: CallInfo) {
        val app = context.applicationContext
        CallNotifier.loadAvatar(app, call.avatar)
        val existing = CallStore.get(app, call.id)
        if (existing?.ongoing == true) return
        CallStore.put(app, call)
        if (!CallNotifier.canPost(app)) return
        CallNotifier.manager(app).notify(CallNotifier.notificationId(call.id), CallNotifier.buildIncoming(app, call))
        handler.removeCallbacksAndMessages(timeoutToken(call.id))
        handler.postAtTime({ timeout(app, call.id) }, timeoutToken(call.id), android.os.SystemClock.uptimeMillis() + call.timeoutMillis)
    }

    /** Converts or creates an ongoing call. Blocking (avatar download); run off the main thread. */
    fun showOngoing(context: Context, call: CallInfo) {
        val app = context.applicationContext
        CallNotifier.loadAvatar(app, call.avatar)
        stopRinging(app, call.id)
        CallStore.put(app, call.copy(ongoing = true))
        CallForegroundService.start(app, call.id)
    }

    fun end(context: Context, callId: String) {
        val app = context.applicationContext
        stopRinging(app, callId)
        CallStore.remove(app, callId)
        CallForegroundService.stop(app, callId)
        CallNotifier.manager(app).cancel(CallNotifier.notificationId(callId))
        if (CallStore.all(app).none { it.ongoing }) CallStore.setAccepted(app, false)
        LockScreenPolicy.refresh(app)
    }

    fun endAll(context: Context) {
        CallStore.all(context).forEach { end(context, it.id) }
        CallStore.setAccepted(context, false)
        LockScreenPolicy.refresh(context)
    }

    // endregion

    // region user actions

    fun accept(context: Context, callId: String, launch: Boolean = true) {
        val app = context.applicationContext
        val call = CallStore.get(app, callId)
        stopRinging(app, callId)
        CallStore.setAccepted(app, true)
        LockScreenPolicy.refresh(app)
        record(app, KIND_ACCEPT, callId, call)
        if (launch) launchApp(app, call?.deepLink.orEmpty())
    }

    fun decline(context: Context, callId: String) {
        val app = context.applicationContext
        val call = CallStore.get(app, callId)
        end(app, callId)
        record(app, KIND_DECLINE, callId, call)
    }

    fun open(context: Context, callId: String, launch: Boolean = true) {
        val app = context.applicationContext
        val call = CallStore.get(app, callId)
        if (call?.ongoing == true) LockScreenPolicy.refresh(app)
        record(app, KIND_OPEN, callId, call)
        if (launch) launchApp(app, call?.deepLink.orEmpty())
    }

    fun hangUp(context: Context, callId: String) {
        val app = context.applicationContext
        val call = CallStore.get(app, callId)
        end(app, callId)
        record(app, KIND_HANG_UP, callId, call)
    }

    fun timeout(context: Context, callId: String) {
        val app = context.applicationContext
        val call = CallStore.get(app, callId) ?: return
        if (call.ongoing) return
        end(app, callId)
        record(app, KIND_TIMEOUT, callId, call)
    }

    // endregion

    // region action channel

    /** Resolves with the oldest queued action, or parks until the next one. */
    fun next(context: Context, completion: ModuleCompletion) {
        val app = context.applicationContext
        var replaced: ModuleCompletion? = null
        val action = synchronized(this) {
            CallStore.popAction(app) ?: run {
                replaced = waiter
                waiter = completion
                null
            }
        }
        replaced?.failure("Action read replaced")
        action?.let { completion.success(wire(it)) }
    }

    internal fun record(context: Context, kind: Int, callId: String, call: CallInfo?) {
        val action = JSONObject()
            .put("kind", kind)
            .put("callId", callId)
            .put("video", call?.video ?: false)
            .put("deepLink", call?.deepLink.orEmpty())
            .put("dataJson", call?.dataJson ?: "{}")
            .put("at", System.currentTimeMillis())
        val receiver = synchronized(this) {
            val pending = waiter
            if (pending == null) CallStore.pushAction(context, action) else waiter = null
            pending
        }
        receiver?.success(wire(action))
    }

    private fun wire(action: JSONObject): Map<String, WireValue> = mapOf(
        "kind" to WireValue.Integer(action.optLong("kind")),
        "callId" to WireValue.Text(action.optString("callId")),
        "video" to WireValue.Flag(action.optBoolean("video")),
        "deepLink" to WireValue.Text(action.optString("deepLink")),
        "dataJson" to WireValue.Text(action.optString("dataJson", "{}")),
        "at" to WireValue.Integer(action.optLong("at")),
    )

    // endregion

    // region helpers

    internal fun registerScreen(activity: Activity) {
        screens += WeakReference(activity)
    }

    internal fun unregisterScreen(activity: Activity) {
        screens.removeIf { it.get() == null || it.get() === activity }
    }

    private fun stopRinging(context: Context, callId: String) {
        handler.removeCallbacksAndMessages(timeoutToken(callId))
        val ringing = CallStore.get(context, callId)
        if (ringing != null && !ringing.ongoing) {
            CallNotifier.manager(context).cancel(CallNotifier.notificationId(callId))
        }
        handler.post {
            screens.mapNotNull { it.get() }
                .filter { (it as? IncomingCallActivity)?.callId == callId }
                .forEach { it.finish() }
        }
    }

    private fun timeoutToken(callId: String): Any = "pam-call-timeout:$callId".intern()

    internal fun launchApp(context: Context, deepLink: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        if (deepLink.isNotBlank()) {
            intent.action = Intent.ACTION_VIEW
            intent.data = Uri.parse(deepLink)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { context.startActivity(intent) }
    }

    // endregion
}
