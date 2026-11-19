package dev.pam.calls

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground service (camera|microphone) owning the ongoing CallStyle
 * notification, so capture continues while the app is in the background.
 * Falls back to a regular ongoing notification when the service cannot be
 * promoted (permissions missing or background start restrictions).
 */
class CallForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callId = intent?.getStringExtra(CallIntents.EXTRA_CALL_ID)
        val call = callId?.let { CallStore.get(this, it) }
        if (call == null || !call.ongoing) {
            // end() won the race against this start (or the process restarted
            // the service): startForegroundService() still requires
            // startForeground(), or Android 8+ kills the app.
            if (foregroundCallId == null) retire(callId, startId)
            return START_NOT_STICKY
        }
        val id = CallNotifier.notificationId(call.id)
        val promoted = (foregroundCallId == null || foregroundCallId == call.id) &&
            promote(id, CallNotifier.buildOngoing(this, call, callStyle = true), serviceTypes(this, call.video))
        if (promoted) {
            foregroundCallId = call.id
        } else {
            CallNotifier.manager(this).notify(id, CallNotifier.buildOngoing(this, call, callStyle = false))
            if (foregroundCallId == null) retire(call.id, startId)
        }
        return START_NOT_STICKY
    }

    private fun promote(id: Int, notification: android.app.Notification, types: Int): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            require(Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || types != 0) { "No foreground service type granted" }
            startForeground(id, notification, types)
        } else {
            startForeground(id, notification)
        }
    }.isSuccess

    /**
     * Satisfies the startForegroundService() contract with a placeholder, then
     * leaves the foreground and stops. Below Android 8 (startService) and when
     * promotion is impossible it only stops.
     */
    private fun retire(callId: String?, startId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Own notification id: never replaces the ringing or fallback notification of a call.
            val placeholder = CallInfo(id = callId?.takeIf { it.isNotBlank() } ?: "pam-call", name = "", ongoing = true)
            if (promote(PLACEHOLDER_ID, CallNotifier.buildOngoing(this, placeholder, callStyle = false), serviceTypes(this, video = true))) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
        stopSelf(startId)
    }

    override fun onDestroy() {
        foregroundCallId = null
        super.onDestroy()
    }

    companion object {
        private const val PLACEHOLDER_ID = 0x29FF_FFFF

        @Volatile
        var foregroundCallId: String? = null
            private set

        fun start(context: Context, callId: String) {
            val intent = Intent(context, CallForegroundService::class.java).putExtra(CallIntents.EXTRA_CALL_ID, callId)
            val call = CallStore.get(context, callId)
            // Android 14+: without a granted type (microphone/camera) the
            // service could never call startForeground(); post the plain notification.
            val promotable = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || serviceTypes(context, call?.video ?: false) != 0
            val started = promotable && runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            }.isSuccess
            if (!started) {
                call?.let {
                    CallNotifier.manager(context).notify(CallNotifier.notificationId(callId), CallNotifier.buildOngoing(context, it, callStyle = false))
                }
            }
        }

        fun stop(context: Context, callId: String) {
            if (foregroundCallId == callId) {
                foregroundCallId = null
                context.stopService(Intent(context, CallForegroundService::class.java))
            }
        }

        fun serviceTypes(context: Context, video: Boolean): Int {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
            var types = 0
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (video && context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            return types
        }
    }
}
