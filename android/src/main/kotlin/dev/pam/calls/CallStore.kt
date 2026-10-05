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
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("avatar", avatar).put("video", video)
        .put("subtitle", subtitle).put("deepLink", deepLink).put("dataJson", dataJson)
        .put("timeoutMillis", timeoutMillis).put("acceptLabel", acceptLabel).put("declineLabel", declineLabel)
        .put("hangUpLabel", hangUpLabel).put("sinceMillis", sinceMillis).put("ongoing", ongoing)
        .put("ringingSince", ringingSince)

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
        )
    }
}

/** SharedPreferences-backed state: calls, queued actions, push mappings and the lock-screen flag. */
object CallStore {
    private const val PREFS = "dev.pam.calls"
    private const val KEY_CALLS = "calls"
    private const val KEY_ACTIONS = "actions"
    private const val KEY_MAPPINGS = "mappings"
    private const val MAX_ACTIONS = 64

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

    /** Apps stay visible over the lock screen while a call was accepted or is ongoing. */
    fun hasActiveCall(context: Context): Boolean = all(context).any { it.ongoing } || prefs(context).getBoolean("accepted", false)

    fun setAccepted(context: Context, accepted: Boolean) {
        prefs(context).edit().putBoolean("accepted", accepted).apply()
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

    private fun actions(context: Context): JSONArray =
        runCatching { JSONArray(prefs(context).getString(KEY_ACTIONS, "[]")) }.getOrDefault(JSONArray())
}
