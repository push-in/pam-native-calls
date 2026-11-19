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
    /** Orders a ringing post against [end], which may run on another thread. */
    private val surfaces = Any()

    // region incoming / ongoing

    /**
     * Posts the ringing surfaces. Blocking (avatar download); run off the main thread.
     * A call that already ended (see [CallStore.markEnded]) never rings again:
     * an "ended" push handled while the avatar downloads, or a received push
     * replayed to PHP after the call ended, must not resurrect it.
     *
     * The call rings at once with the avatar already in memory (or none); a
     * missing avatar is fetched afterwards and the same notification is
     * reposted silently ([Notification.Builder.setOnlyAlertOnce]).
     */
    fun showIncoming(context: Context, call: CallInfo) {
        val app = context.applicationContext
        val posted = synchronized(surfaces) {
            if (CallStore.isEnded(app, call.id)) return
            val existing = CallStore.get(app, call.id)
            if (existing?.ongoing == true) return
            CallStore.put(app, call)
            if (!CallNotifier.canPost(app)) return
            CallNotifier.manager(app).notify(CallNotifier.notificationId(call.id), CallNotifier.buildIncoming(app, call))
            handler.removeCallbacksAndMessages(timeoutToken(call.id))
            handler.postAtTime({ timeout(app, call.id) }, timeoutToken(call.id), android.os.SystemClock.uptimeMillis() + call.timeoutMillis)
            CallNotifier.cachedAvatar(call.avatar) != null || call.avatar.isBlank()
        }
        if (posted) return
        CallNotifier.loadAvatar(app, call.avatar) ?: return
        synchronized(surfaces) {
            val current = CallStore.get(app, call.id)
            if (CallStore.isEnded(app, call.id) || current == null || current.ongoing) return
            CallNotifier.manager(app).notify(CallNotifier.notificationId(call.id), CallNotifier.buildIncoming(app, current, update = true))
        }
    }

    /** Converts or creates an ongoing call. Blocking (avatar download); run off the main thread. */
    fun showOngoing(context: Context, call: CallInfo) {
        val app = context.applicationContext
        val ends = endCount(call.id)
        CallNotifier.loadAvatar(app, call.avatar)
        synchronized(surfaces) {
            // end() ran while the avatar downloaded: the call must not come back.
            // (An end() before this call is fine: apps clear the ringing UI on
            // accept and publish the ongoing call once media connects.)
            if (endCount(call.id) != ends) return
            stopRinging(app, call.id)
            CallStore.put(app, call.copy(ongoing = true))
        }
        LockScreenPolicy.refresh(app)
        CallForegroundService.start(app, call.id)
    }

    fun end(context: Context, callId: String) {
        val app = context.applicationContext
        val wasOngoing: Boolean
        synchronized(surfaces) {
            endCounts.merge(callId, 1L, Long::plus)
            wasOngoing = CallStore.get(app, callId)?.ongoing == true
            CallStore.markEnded(app, callId)
            stopRinging(app, callId)
            CallStore.remove(app, callId)
        }
        CallForegroundService.stop(app, callId)
        CallNotifier.manager(app).cancel(CallNotifier.notificationId(callId))
        releaseAccepted(app, callId, wasOngoing)
        CallPictureInPicture.callsChanged(app)
        LockScreenPolicy.refresh(app)
    }

    fun endAll(context: Context) {
        CallStore.all(context).forEach { end(context, it.id) }
        CallStore.setAccepted(context, null)
        handler.removeCallbacksAndMessages(ACCEPT_EXPIRY)
        LockScreenPolicy.refresh(context)
    }

    /**
     * The accepted call keeps the app over the lock screen until it hangs up
     * after being ongoing. Removing its surfaces before `showOngoing` (apps
     * clear the ringing UI on accept and publish the ongoing call once media
     * connects) keeps it for [CallStore.ACCEPT_GRACE_MILLIS] after the
     * acceptance; ending any other call never releases it.
     */
    private fun releaseAccepted(app: Context, callId: String, wasOngoing: Boolean) {
        val accepted = CallStore.acceptedCallId(app) ?: return
        val now = System.currentTimeMillis()
        if (accepted == callId) {
            val remaining = CallStore.acceptedAt(app) + CallStore.ACCEPT_GRACE_MILLIS - now
            if (wasOngoing || remaining <= 0) {
                CallStore.setAccepted(app, null)
            } else {
                handler.removeCallbacksAndMessages(ACCEPT_EXPIRY)
                handler.postAtTime({ expireAccepted(app, callId) }, ACCEPT_EXPIRY, android.os.SystemClock.uptimeMillis() + remaining + 50)
            }
        } else if (!CallStore.acceptedHolds(app, now)) {
            CallStore.setAccepted(app, null)
        }
    }

    private fun expireAccepted(app: Context, callId: String) {
        if (CallStore.acceptedCallId(app) != callId || CallStore.acceptedHolds(app)) return
        CallStore.setAccepted(app, null)
        LockScreenPolicy.refresh(app)
    }

    // endregion

    // region user actions

    fun accept(context: Context, callId: String, launch: Boolean = true) {
        val app = context.applicationContext
        val call = CallStore.get(app, callId)
        stopRinging(app, callId)
        CallStore.setAccepted(app, callId)
        LockScreenPolicy.refresh(app)
        record(app, KIND_ACCEPT, callId, call)
        if (launch) launchApp(app, call?.deepLink.orEmpty())
    }

    /**
     * Declines a ringing call. With a decline endpoint (PushCallMapping::declineEndpoint
     * or IncomingCall::declineEndpoint) the server is told natively, PHP
     * suspended or process just woken by the push, and the Decline action
     * reports `serverNotified`. [done] runs once the action is recorded
     * (BroadcastReceiver.goAsync).
     */
    fun decline(context: Context, callId: String, done: (() -> Unit)? = null) {
        val app = context.applicationContext
        val call = CallStore.get(app, callId)
        end(app, callId)
        if (call == null || call.declineUrl.isBlank()) {
            record(app, KIND_DECLINE, callId, call)
            done?.invoke()
            return
        }
        io.execute {
            try {
                val notified = runCatching { CallDeclineEndpoint.send(call) }.getOrDefault(false)
                record(app, KIND_DECLINE, callId, call, serverNotified = notified)
            } finally {
                done?.invoke()
            }
        }
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

    internal fun record(context: Context, kind: Int, callId: String, call: CallInfo?, serverNotified: Boolean = false) {
        val action = JSONObject()
            .put("kind", kind)
            .put("callId", callId)
            .put("video", call?.video ?: false)
            .put("deepLink", call?.deepLink.orEmpty())
            .put("dataJson", call?.dataJson ?: "{}")
            .put("at", System.currentTimeMillis())
            .put("serverNotified", serverNotified)
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
        "serverNotified" to WireValue.Flag(action.optBoolean("serverNotified")),
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

    private val ACCEPT_EXPIRY = Any()

    /** end() calls per call id in this process (orders showOngoing's avatar download against end). */
    private val endCounts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun endCount(callId: String): Long = endCounts[callId] ?: 0L

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
