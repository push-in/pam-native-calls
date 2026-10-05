package dev.pam.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Builds and posts call notifications; loads caller avatars. */
object CallNotifier {
    const val CHANNEL_INCOMING = "pam_calls_incoming"
    const val CHANNEL_ONGOING = "pam_calls_ongoing"
    private const val AVATAR_SIZE = 192
    private val avatars = LruCache<String, Bitmap>(16)

    fun notificationId(callId: String): Int = 0x2A00_0000 + (callId.hashCode() and 0x00FF_FFFF)

    fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    fun ensureChannels(context: Context) {
        val manager = manager(context)
        if (manager.getNotificationChannel(CHANNEL_INCOMING) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_INCOMING, context.getString(R.string.pam_calls_channel_incoming), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.pam_calls_channel_incoming_description)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 700, 500, 700, 1_200)
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                },
            )
        }
        if (manager.getNotificationChannel(CHANNEL_ONGOING) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ONGOING, context.getString(R.string.pam_calls_channel_ongoing), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.pam_calls_channel_ongoing_description)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }
    }

    fun canPost(context: Context): Boolean = manager(context).areNotificationsEnabled()

    fun buildIncoming(context: Context, call: CallInfo): Notification {
        ensureChannels(context)
        val avatar = cachedAvatar(call.avatar)
        val accept = CallIntents.activity(context, call.id, CallIntents.ACTION_ACCEPT)
        val decline = CallIntents.broadcast(context, call.id, CallIntents.ACTION_DECLINE)
        val fullScreen = CallIntents.incomingScreen(context, call.id)
        val subtitle = call.subtitle.ifBlank {
            context.getString(if (call.video) R.string.pam_calls_incoming_video else R.string.pam_calls_incoming_voice)
        }
        val builder = Notification.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.pam_calls_ic_call)
            .setContentTitle(call.name)
            .setContentText(subtitle)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(true)
            .setWhen(call.ringingSince)
            .setContentIntent(CallIntents.activity(context, call.id, CallIntents.ACTION_OPEN))
            .setFullScreenIntent(fullScreen, true)
            .setDeleteIntent(CallIntents.broadcast(context, call.id, CallIntents.ACTION_TIMEOUT))
            .setTimeoutAfter(call.timeoutMillis)
        avatar?.let { builder.setLargeIcon(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(
                Notification.CallStyle.forIncomingCall(person(call, avatar), decline, accept).setIsVideo(call.video),
            )
        } else {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.pam_calls_ic_call_end),
                    call.declineLabel.ifBlank { context.getString(R.string.pam_calls_decline) },
                    decline,
                ).build(),
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.pam_calls_ic_call),
                    call.acceptLabel.ifBlank { context.getString(R.string.pam_calls_accept) },
                    accept,
                ).build(),
            )
        }
        return builder.build().apply { flags = flags or Notification.FLAG_INSISTENT }
    }

    fun buildOngoing(context: Context, call: CallInfo, callStyle: Boolean): Notification {
        ensureChannels(context)
        val avatar = cachedAvatar(call.avatar)
        val hangUp = CallIntents.broadcast(context, call.id, CallIntents.ACTION_HANG_UP)
        val builder = Notification.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.pam_calls_ic_call)
            .setContentTitle(call.name.ifBlank { context.getString(R.string.pam_calls_ongoing) })
            .setContentText(call.subtitle.ifBlank { context.getString(R.string.pam_calls_ongoing_tap) })
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setWhen(call.sinceMillis)
            .setContentIntent(CallIntents.activity(context, call.id, CallIntents.ACTION_OPEN))
        avatar?.let { builder.setLargeIcon(it) }
        if (callStyle && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(Notification.CallStyle.forOngoingCall(person(call, avatar), hangUp).setIsVideo(call.video))
        } else {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.pam_calls_ic_call_end),
                    call.hangUpLabel.ifBlank { context.getString(R.string.pam_calls_hang_up) },
                    hangUp,
                ).build(),
            )
        }
        return builder.build()
    }

    @android.annotation.SuppressLint("NewApi") // only called behind SDK_INT >= S checks
    private fun person(call: CallInfo, avatar: Bitmap?): Person = Person.Builder()
        .setName(call.name.ifBlank { " " })
        .setImportant(true)
        .apply { avatar?.let { setIcon(Icon.createWithBitmap(it)) } }
        .build()

    fun cachedAvatar(source: String): Bitmap? = if (source.isBlank()) null else avatars.get(source)

    /** Blocking, bounded avatar load (call off the main thread). */
    fun loadAvatar(context: Context, source: String, timeoutMillis: Int = 3_000): Bitmap? {
        if (source.isBlank()) return null
        avatars.get(source)?.let { return it }
        val bitmap = runCatching {
            val bytes = if (source.startsWith("http://", true) || source.startsWith("https://", true)) {
                (URL(source).openConnection() as HttpURLConnection).run {
                    connectTimeout = timeoutMillis
                    readTimeout = timeoutMillis
                    instanceFollowRedirects = true
                    try {
                        if (responseCode !in 200..299) return@runCatching null
                        inputStream.use { it.readNBytesCompat(4 * 1024 * 1024) }
                    } finally {
                        disconnect()
                    }
                }
            } else {
                val root = File(context.filesDir, "pam-files").canonicalFile
                val file = File(root, source.trimStart('/')).canonicalFile
                if (!file.path.startsWith(root.path + File.separator) || !file.isFile) return@runCatching null
                file.readBytes()
            }
            decodeCircle(bytes)
        }.getOrNull() ?: return null
        avatars.put(source, bitmap)
        return bitmap
    }

    private fun decodeCircle(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= AVATAR_SIZE && bounds.outHeight / (sample * 2) >= AVATAR_SIZE) sample *= 2
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val side = minOf(source.width, source.height)
        val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, AVATAR_SIZE, AVATAR_SIZE, true)
        val output = Bitmap.createBitmap(AVATAR_SIZE, AVATAR_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawCircle(AVATAR_SIZE / 2f, AVATAR_SIZE / 2f, AVATAR_SIZE / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        return output
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            require(output.size() <= limit) { "Avatar is too large" }
        }
        return output.toByteArray()
    }
}

/** PendingIntent factory for call surfaces. */
object CallIntents {
    const val ACTION_ACCEPT = "dev.pam.calls.ACCEPT"
    const val ACTION_DECLINE = "dev.pam.calls.DECLINE"
    const val ACTION_OPEN = "dev.pam.calls.OPEN"
    const val ACTION_HANG_UP = "dev.pam.calls.HANG_UP"
    const val ACTION_TIMEOUT = "dev.pam.calls.TIMEOUT"
    const val EXTRA_CALL_ID = "dev.pam.calls.CALL_ID"

    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun requestCode(callId: String, action: String) = (callId + action).hashCode()

    fun activity(context: Context, callId: String, action: String): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode(callId, action),
        Intent(context, CallActionActivity::class.java).setAction(action).putExtra(EXTRA_CALL_ID, callId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        FLAGS,
    )

    fun broadcast(context: Context, callId: String, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode(callId, action),
        Intent(context, CallActionReceiver::class.java).setAction(action).putExtra(EXTRA_CALL_ID, callId),
        FLAGS,
    )

    fun incomingScreen(context: Context, callId: String): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode(callId, "screen"),
        Intent(context, IncomingCallActivity::class.java).putExtra(EXTRA_CALL_ID, callId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        FLAGS,
    )
}
