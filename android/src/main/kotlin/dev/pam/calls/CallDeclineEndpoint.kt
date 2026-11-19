package dev.pam.calls

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Tells the call server that the user declined, natively: the Decline button
 * of the notification or of the lock-screen UI works with the PHP runtime
 * suspended or the process just woken by the push.
 *
 * `POST <declineUrl>` with `{"call_id": "...", "action": "decline"}` and,
 * when the push carried one, `Authorization: Bearer <declineToken>` (a
 * short-lived, single-use token issued for this call). Any 2xx counts as
 * delivered. The app's network security config still applies (cleartext).
 */
internal object CallDeclineEndpoint {
    private const val CONNECT_TIMEOUT = 4_000
    private const val READ_TIMEOUT = 5_000

    fun accepts(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

    /** Blocking; run off the main thread. True when the server answered 2xx. */
    fun send(call: CallInfo): Boolean {
        if (!accepts(call.declineUrl)) return false
        val body = JSONObject().put("call_id", call.id).put("action", "decline").toString().toByteArray()
        val connection = URL(call.declineUrl).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            if (call.declineToken.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer ${call.declineToken.trim()}")
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            runCatching { (if (status >= 400) connection.errorStream else connection.inputStream)?.use { it.skip(64 * 1024) } }
            status in 200..299
        } finally {
            connection.disconnect()
        }
    }
}
