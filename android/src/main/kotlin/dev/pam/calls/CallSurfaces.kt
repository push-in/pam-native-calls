package dev.pam.calls

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import dev.pam.nativeapp.push.BackgroundPush
import org.json.JSONObject

/** Handles actions that must not open the app: Decline, HangUp and the ring timeout. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(CallIntents.EXTRA_CALL_ID) ?: return
        when (intent.action) {
            CallIntents.ACTION_DECLINE -> CallController.decline(context, callId)
            CallIntents.ACTION_HANG_UP -> CallController.hangUp(context, callId)
            CallIntents.ACTION_TIMEOUT -> CallController.timeout(context, callId)
        }
    }
}

/**
 * Invisible trampoline for Accept/Open. Activities (not broadcasts) must start
 * the app from a notification on Android 12+, and recording the action here
 * keeps it even if PHP is suspended or the process was killed.
 */
class CallActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun handle(intent: Intent?) {
        val callId = intent?.getStringExtra(CallIntents.EXTRA_CALL_ID) ?: return
        when (intent.action) {
            CallIntents.ACTION_ACCEPT -> CallController.accept(this, callId)
            CallIntents.ACTION_OPEN -> CallController.open(this, callId)
        }
    }
}

/** Shows incoming calls described by data pushes while the PHP runtime is suspended. */
class CallPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BackgroundPush.ACTION_RECEIVED) return
        val data = runCatching { JSONObject(intent.getStringExtra(BackgroundPush.EXTRA_DATA_JSON).orEmpty()) }.getOrNull() ?: return
        val match = PushCallMatcher.match(CallStore.mappings(context), data, intent.getStringExtra(BackgroundPush.EXTRA_TITLE).orEmpty()) ?: return
        val app = context.applicationContext
        when (match) {
            is PushCallMatcher.Match.Ended -> CallController.end(app, match.callId)
            is PushCallMatcher.Match.Incoming -> {
                val pending = goAsync()
                CallController.io.execute {
                    try {
                        CallController.showIncoming(app, match.call)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}

/** Pure evaluation of persisted push mappings against push data. */
object PushCallMatcher {
    sealed interface Match {
        data class Incoming(val call: CallInfo) : Match
        data class Ended(val callId: String) : Match
    }

    fun match(mappings: org.json.JSONArray, data: JSONObject, fallbackTitle: String = ""): Match? {
        for (index in 0 until mappings.length()) {
            val mapping = mappings.optJSONObject(index) ?: continue
            val type = data.text(mapping.optString("typeField", "type"))
            if (type == null || type !in mapping.strings("typeValues")) continue
            val callId = data.text(mapping.optString("idField", "call_id"))?.trim()
                ?.takeIf { runCatching { CallInfo.validId(it) }.isSuccess } ?: continue
            val endedField = mapping.optString("endedField")
            if (endedField.isNotEmpty() && data.text(endedField) in mapping.strings("endedValues")) {
                return Match.Ended(callId)
            }
            val videoField = mapping.optString("videoField")
            val video = videoField.isNotEmpty() && data.text(videoField)?.lowercase() in mapping.strings("videoValues").map { it.lowercase() }
            val field = { key: String -> mapping.optString(key).takeIf { it.isNotEmpty() }?.let { data.text(it) }.orEmpty() }
            val dataJson = JSONObject().apply { data.keys().forEach { key -> put(key, data.opt(key)) } }.toString()
            return Match.Incoming(
                CallInfo(
                    id = callId,
                    name = field("nameField").ifBlank { fallbackTitle }.take(256),
                    avatar = field("avatarField").take(2048),
                    video = video,
                    subtitle = field("subtitleField").take(256),
                    deepLink = field("deepLinkField").take(2048),
                    dataJson = dataJson,
                    timeoutMillis = mapping.optLong("timeoutMillis", 45_000).coerceIn(5_000, 300_000),
                    acceptLabel = mapping.optString("acceptLabel"),
                    declineLabel = mapping.optString("declineLabel"),
                ),
            )
        }
        return null
    }

    private fun JSONObject.text(key: String): String? =
        if (!has(key) || isNull(key)) null else opt(key)?.toString()

    private fun JSONObject.strings(key: String): List<String> =
        optJSONArray(key)?.let { array -> List(array.length()) { array.optString(it) } }.orEmpty()
}
