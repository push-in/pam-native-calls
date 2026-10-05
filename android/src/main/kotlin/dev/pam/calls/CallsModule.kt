package dev.pam.calls

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.pam.nativeapp.modules.ModuleCompletion
import dev.pam.nativeapp.modules.NativeModule
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue

/** PAM module `calls`. */
class CallsModule(private val context: Context) : NativeModule {
    init {
        LockScreenPolicy.install(context)
    }

    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            val values = WireMap.decode(payload)
            val app = context.applicationContext
            when (method) {
                "showIncoming" -> {
                    val call = incoming(values)
                    CallController.io.execute {
                        runCatching { CallController.showIncoming(app, call) }
                            .onSuccess { completion.success() }
                            .onFailure { completion.failure(it.message ?: "Could not show incoming call") }
                    }
                }
                "showOngoing" -> {
                    val call = ongoing(values)
                    CallController.io.execute {
                        runCatching { CallController.showOngoing(app, call) }
                            .onSuccess { completion.success() }
                            .onFailure { completion.failure(it.message ?: "Could not show ongoing call") }
                    }
                }
                "end" -> {
                    CallController.end(app, CallInfo.validId(values.text("callId")))
                    completion.success()
                }
                "endAll" -> {
                    CallController.endAll(app)
                    completion.success()
                }
                "next" -> CallController.next(app, completion)
                "configurePush" -> {
                    CallStore.setMappings(app, values.text("mappingsJson"))
                    completion.success()
                }
                "readiness" -> completion.success(readiness(app))
                "openFullScreenSettings" -> {
                    openSettings(app)
                    completion.success()
                }
                else -> completion.failure("Unknown calls method $method")
            }
        }.onFailure { completion.failure(it.message ?: "Calls operation failed") }
    }

    private fun incoming(values: Map<String, WireValue>) = CallInfo(
        id = CallInfo.validId(values.text("callId")),
        name = values.text("name").take(256),
        avatar = values.text("avatar", "").take(2048),
        video = values.flag("video"),
        subtitle = values.text("subtitle", "").take(256),
        deepLink = values.text("deepLink", "").take(2048),
        dataJson = values.text("dataJson", "{}"),
        timeoutMillis = values.integer("timeoutMillis", 45_000).coerceIn(5_000, 300_000),
        acceptLabel = values.text("acceptLabel", "").take(64),
        declineLabel = values.text("declineLabel", "").take(64),
    )

    private fun ongoing(values: Map<String, WireValue>): CallInfo {
        val id = CallInfo.validId(values.text("callId"))
        val known = CallStore.get(context, id)
        return CallInfo(
            id = id,
            name = values.text("name", "").ifBlank { known?.name.orEmpty() }.take(256),
            avatar = values.text("avatar", "").ifBlank { known?.avatar.orEmpty() }.take(2048),
            video = values.flag("video", known?.video ?: false),
            subtitle = values.text("subtitle", "").take(256),
            deepLink = values.text("deepLink", "").ifBlank { known?.deepLink.orEmpty() }.take(2048),
            dataJson = known?.dataJson ?: "{}",
            hangUpLabel = values.text("hangUpLabel", "").take(64),
            sinceMillis = values.integer("sinceMillis", System.currentTimeMillis()),
            ongoing = true,
        )
    }

    private fun readiness(context: Context): Map<String, WireValue> {
        val manager = context.getSystemService(NotificationManager::class.java)
        val fullScreen = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || manager.canUseFullScreenIntent()
        return mapOf(
            "notificationsEnabled" to WireValue.Flag(manager.areNotificationsEnabled()),
            "fullScreenIntentAllowed" to WireValue.Flag(fullScreen),
            "callStyleSupported" to WireValue.Flag(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S),
        )
    }

    private fun openSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
