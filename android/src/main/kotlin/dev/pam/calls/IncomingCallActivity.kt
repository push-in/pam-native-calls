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
import android.graphics.Rect
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ScrollView
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

    /**
     * Header, flexible gap, actions in one scrolling column: on a phone held in
     * landscape (360-412dp tall) the actions follow the name instead of
     * covering it, and system bars (status, navigation, cutout) pad the column.
     */
    private fun layout(call: CallInfo): View {
        val compact = resources.configuration.screenHeightDp in 1 until COMPACT_HEIGHT_DP
        val root = ScrollView(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.rgb(11, 20, 26), Color.rgb(31, 44, 52)))
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = TAG_COLUMN
        }
        root.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val bars = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()).let { Rect(it.left, it.top, it.right, it.bottom) }
            } else {
                Rect(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            column.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(if (compact) 24 else 96), dp(24), 0)
            tag = TAG_HEADER
        }
        val avatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(55, 71, 79))
            }
            contentDescription = call.name
        }
        val avatarSize = dp(if (compact) 72 else 112)
        header.addView(avatar, LinearLayout.LayoutParams(avatarSize, avatarSize))
        val cached = CallNotifier.cachedAvatar(call.avatar)
        if (cached != null) {
            avatar.setImageBitmap(cached)
        } else if (call.avatar.isNotBlank()) {
            CallController.io.execute {
                val bitmap = CallNotifier.loadAvatar(this, call.avatar)
                if (bitmap != null) runOnUiThread { if (!isDestroyed) avatar.setImageBitmap(bitmap) }
            }
        }
        header.addView(text(call.name, if (compact) 24f else 28f, Color.WHITE, bold = true).apply { setPadding(0, dp(if (compact) 12 else 24), 0, dp(8)) })
        val subtitle = call.subtitle.ifBlank {
            getString(if (call.video) R.string.pam_calls_incoming_video else R.string.pam_calls_incoming_voice)
        }
        header.addView(text(subtitle, 16f, Color.rgb(176, 190, 197)))
        column.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(View(this), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24), 1f))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), dp(if (compact) 24 else 72))
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
        column.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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
        const val TAG_HEADER = "pam-calls-header"
        const val TAG_COLUMN = "pam-calls-column"
        private const val COMPACT_HEIGHT_DP = 520
    }
}
