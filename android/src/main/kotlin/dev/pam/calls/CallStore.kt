package dev.pam.calls

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** A call known to the plugin (ringing or ongoing). Persisted so surfaces survive process death. */
data class CallInfo(
    val id: String,
    val name: String,
    val avatar: String = "",
    val video: Boolean = false,
    val subtitle: String = "",
    val deepLink: String = "",
    val dataJson: String = "{}",
    val timeoutMillis: Long = 45_000,
    val acceptLabel: String = "",
    val declineLabel: String = "",
    val hangUpLabel: String = "",
    val sinceMillis: Long = System.currentTimeMillis(),
    val ongoing: Boolean = false,
    val ringingSince: Long = System.currentTimeMillis(),
    /** Endpoint the plugin calls natively when the user declines (see PushCallMapping::declineEndpoint). */
    val declineUrl: String = "",
    val declineToken: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("avatar", avatar).put("video", video)
        .put("subtitle", subtitle).put("deepLink", deepLink).put("dataJson", dataJson)
        .put("timeoutMillis", timeoutMillis).put("acceptLabel", acceptLabel).put("declineLabel", declineLabel)
        .put("hangUpLabel", hangUpLabel).put("sinceMillis", sinceMillis).put("ongoing", ongoing)
        .put("ringingSince", ringingSince)
        .put("declineUrl", declineUrl)
        .put("declineToken", declineToken)

    companion object {
        private val ID = Regex("[A-Za-z0-9_.:-]{1,128}")

        fun validId(id: String): String = id.trim().also { require(it.matches(ID)) { "Invalid call id" } }

        fun fromJson(json: JSONObject) = CallInfo(
            id = json.getString("id"),
            name = json.optString("name"),
            avatar = json.optString("avatar"),
            video = json.optBoolean("video"),
            subtitle = json.optString("subtitle"),
            deepLink = json.optString("deepLink"),
            dataJson = json.optString("dataJson", "{}"),
            timeoutMillis = json.optLong("timeoutMillis", 45_000),
            acceptLabel = json.optString("acceptLabel"),
            declineLabel = json.optString("declineLabel"),
            hangUpLabel = json.optString("hangUpLabel"),
            sinceMillis = json.optLong("sinceMillis", System.currentTimeMillis()),
            ongoing = json.optBoolean("ongoing"),
            ringingSince = json.optLong("ringingSince", System.currentTimeMillis()),
            declineUrl = json.optString("declineUrl"),
            declineToken = json.optString("declineToken"),
        )
    }
}

/** SharedPreferences-backed state: calls, queued actions, push mappings, ended calls and the lock-screen flag. */
object CallStore {
    private const val PREFS = "dev.pam.calls"
    private const val KEY_CALLS = "calls"
    private const val KEY_ACTIONS = "actions"
    private const val KEY_MAPPINGS = "mappings"
    private const val KEY_ENDED = "ended"
    private const val MAX_ACTIONS = 64
    private const val MAX_ENDED = 64
    private const val KEY_ACCEPTED = "acceptedCall"
    private const val KEY_ACCEPTED_AT = "acceptedAt"
    private const val LEGACY_ACCEPTED = "accepted"

    /**
     * How long an accepted call keeps the app over the lock screen after the
     * app removed its surfaces (`end`) without `showOngoing`: apps that clear
     * the ringing UI on accept and publish the ongoing call once media
     * connects would otherwise drop behind the keyguard while connecting.
     */
    const val ACCEPT_GRACE_MILLIS = 30_000L

    /** How long an ended call id refuses to ring again (replayed or late pushes). */
    const val ENDED_TTL_MILLIS = 60 * 60 * 1000L

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun put(context: Context, call: CallInfo) {
        val calls = calls(context).put(call.id, call.toJson())
        prefs(context).edit().putString(KEY_CALLS, calls.toString()).apply()
    }

    @Synchronized
    fun get(context: Context, id: String): CallInfo? =
        calls(context).optJSONObject(id)?.let { runCatching { CallInfo.fromJson(it) }.getOrNull() }

    @Synchronized
    fun remove(context: Context, id: String): CallInfo? {
        val calls = calls(context)
        val removed = calls.optJSONObject(id)?.let { runCatching { CallInfo.fromJson(it) }.getOrNull() }
        calls.remove(id)
        prefs(context).edit().putString(KEY_CALLS, calls.toString()).apply()
        return removed
    }

    @Synchronized
    fun all(context: Context): List<CallInfo> {
        val calls = calls(context)
        return calls.keys().asSequence().mapNotNull { key ->
            calls.optJSONObject(key)?.let { runCatching { CallInfo.fromJson(it) }.getOrNull() }
        }.toList()
    }

    /**
     * Apps stay visible over the lock screen while a call is ongoing or was
     * accepted: the accepted call keeps it while it is still known (ringing
     * surfaces removed, media connecting) and for [ACCEPT_GRACE_MILLIS] after
     * acceptance once the app removed it before calling `showOngoing`.
     */
    fun hasActiveCall(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        all(context).any { it.ongoing } || acceptedHolds(context, now)

    fun acceptedHolds(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val id = acceptedCallId(context) ?: return false
        val at = prefs(context).getLong(KEY_ACCEPTED_AT, 0L)
        return get(context, id) != null || (at in 1..now && now - at < ACCEPT_GRACE_MILLIS)
    }

    fun acceptedCallId(context: Context): String? = prefs(context).getString(KEY_ACCEPTED, null)?.takeIf { it.isNotEmpty() }

    fun acceptedAt(context: Context): Long = prefs(context).getLong(KEY_ACCEPTED_AT, 0L)

    /** Marks [callId] as the accepted call, or clears it (null). */
    fun setAccepted(context: Context, callId: String?, now: Long = System.currentTimeMillis()) {
        prefs(context).edit().apply {
            remove(LEGACY_ACCEPTED)
            if (callId == null) remove(KEY_ACCEPTED).remove(KEY_ACCEPTED_AT) else putString(KEY_ACCEPTED, callId).putLong(KEY_ACCEPTED_AT, now)
        }.apply()
    }

    @SuppressLint("ApplySharedPref") // actions must survive process death
    @Synchronized
    fun pushAction(context: Context, action: JSONObject) {
        val actions = actions(context)
        actions.put(action)
        while (actions.length() > MAX_ACTIONS) actions.remove(0)
        prefs(context).edit().putString(KEY_ACTIONS, actions.toString()).commit()
    }

    @SuppressLint("ApplySharedPref") // actions must survive process death
    @Synchronized
    fun popAction(context: Context): JSONObject? {
        val actions = actions(context)
        if (actions.length() == 0) return null
        val first = actions.getJSONObject(0)
        actions.remove(0)
        prefs(context).edit().putString(KEY_ACTIONS, actions.toString()).commit()
        return first
    }

    @Synchronized
    fun pendingActions(context: Context): Int = actions(context).length()

    /**
     * Remembers that a call ended so a late or replayed incoming push (or a
     * PHP `showIncoming` for it) never rings it again.
     */
    @SuppressLint("ApplySharedPref") // must survive process death right after an "ended" push
    @Synchronized
    fun markEnded(context: Context, id: String, now: Long = System.currentTimeMillis()) {
        val ended = ended(context, now).put(id, now)
        while (ended.length() > MAX_ENDED) {
            val oldest = ended.keys().asSequence().minByOrNull { ended.optLong(it) } ?: break
            ended.remove(oldest)
        }
        prefs(context).edit().putString(KEY_ENDED, ended.toString()).commit()
    }

    @Synchronized
    fun isEnded(context: Context, id: String, now: Long = System.currentTimeMillis()): Boolean = ended(context, now).has(id)

    fun setMappings(context: Context, json: String) {
        JSONArray(json)
        prefs(context).edit().putString(KEY_MAPPINGS, json).apply()
    }

    fun mappings(context: Context): JSONArray =
        runCatching { JSONArray(prefs(context).getString(KEY_MAPPINGS, "[]")) }.getOrDefault(JSONArray())

    @SuppressLint("ApplySharedPref") // actions must survive process death
    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().clear().commit()
    }

    private fun calls(context: Context): JSONObject =
        runCatching { JSONObject(prefs(context).getString(KEY_CALLS, "{}")!!) }.getOrDefault(JSONObject())

    /** Ended call ids still inside [ENDED_TTL_MILLIS] (expired ones are dropped). */
    private fun ended(context: Context, now: Long): JSONObject {
        val stored = runCatching { JSONObject(prefs(context).getString(KEY_ENDED, "{}")!!) }.getOrDefault(JSONObject())
        stored.keys().asSequence().toList().forEach { key ->
            val at = stored.optLong(key)
            if (at <= 0 || now - at >= ENDED_TTL_MILLIS || at - now > ENDED_TTL_MILLIS) stored.remove(key)
        }
        return stored
    }

    private fun actions(context: Context): JSONArray =
        runCatching { JSONArray(prefs(context).getString(KEY_ACTIONS, "[]")) }.getOrDefault(JSONArray())
}
