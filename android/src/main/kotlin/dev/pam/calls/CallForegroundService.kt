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
            if (foregroundCallId == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        val id = CallNotifier.notificationId(call.id)
        val types = serviceTypes(this, call.video)
        val promoted = (foregroundCallId == null || foregroundCallId == call.id) && runCatching {
            val notification = CallNotifier.buildOngoing(this, call, callStyle = true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                require(Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || types != 0) { "No foreground service type granted" }
                startForeground(id, notification, types)
            } else {
                startForeground(id, notification)
            }
        }.isSuccess
        if (promoted) {
            foregroundCallId = call.id
        } else {
            CallNotifier.manager(this).notify(id, CallNotifier.buildOngoing(this, call, callStyle = false))
            if (foregroundCallId == null) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        foregroundCallId = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var foregroundCallId: String? = null
            private set

        fun start(context: Context, callId: String) {
            val intent = Intent(context, CallForegroundService::class.java).putExtra(CallIntents.EXTRA_CALL_ID, callId)
            val started = runCatching { context.startForegroundService(intent) }.isSuccess
            if (!started) {
                CallStore.get(context, callId)?.let {
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
