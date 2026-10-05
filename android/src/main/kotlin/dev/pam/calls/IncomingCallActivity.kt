package dev.pam.calls

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Native full-screen incoming call UI, launched by the notification's
 * full-screen intent on the lock screen. Works with the PHP runtime suspended.
 */
class IncomingCallActivity : Activity() {
    var callId: String = ""
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        callId = intent.getStringExtra(CallIntents.EXTRA_CALL_ID).orEmpty()
        val call = CallStore.get(this, callId)
        if (call == null || call.ongoing) {
            finish()
            return
        }
        CallController.registerScreen(this)
        setContentView(layout(call))
    }

    override fun onDestroy() {
        CallController.unregisterScreen(this)
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun layout(call: CallInfo): View {
        val root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.rgb(11, 20, 26), Color.rgb(31, 44, 52)))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(96), dp(24), 0)
        }
        val avatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(55, 71, 79))
            }
            contentDescription = call.name
        }
        header.addView(avatar, LinearLayout.LayoutParams(dp(112), dp(112)))
        val cached = CallNotifier.cachedAvatar(call.avatar)
        if (cached != null) {
            avatar.setImageBitmap(cached)
        } else if (call.avatar.isNotBlank()) {
            CallController.io.execute {
                val bitmap = CallNotifier.loadAvatar(this, call.avatar)
                if (bitmap != null) runOnUiThread { avatar.setImageBitmap(bitmap) }
            }
        }
        header.addView(text(call.name, 28f, Color.WHITE, bold = true).apply { setPadding(0, dp(24), 0, dp(8)) })
        val subtitle = call.subtitle.ifBlank {
            getString(if (call.video) R.string.pam_calls_incoming_video else R.string.pam_calls_incoming_voice)
        }
        header.addView(text(subtitle, 16f, Color.rgb(176, 190, 197)))
        root.addView(header, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), dp(72))
        }
        actions.addView(
            action(
                R.drawable.pam_calls_ic_call_end,
                Color.rgb(229, 57, 53),
                call.declineLabel.ifBlank { getString(R.string.pam_calls_decline) },
                TAG_DECLINE,
            ) {
                CallController.decline(this, callId)
                finish()
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        actions.addView(
            action(
                R.drawable.pam_calls_ic_call,
                Color.rgb(67, 160, 71),
                call.acceptLabel.ifBlank { getString(R.string.pam_calls_accept) },
                TAG_ACCEPT,
            ) {
                CallController.accept(this, callId)
                finish()
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(actions, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        return root
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        gravity = Gravity.CENTER
        maxLines = 2
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun action(icon: Int, color: Int, label: String, tag: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val button = ImageView(context).apply {
                setImageResource(icon)
                scaleType = ImageView.ScaleType.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
                contentDescription = label
                this.tag = tag
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
            addView(button, LinearLayout.LayoutParams(dp(72), dp(72)))
            addView(this@IncomingCallActivity.text(label, 14f, Color.WHITE).apply { setPadding(0, dp(12), 0, 0) })
        }

    companion object {
        const val TAG_ACCEPT = "pam-calls-accept"
        const val TAG_DECLINE = "pam-calls-decline"
    }
}
