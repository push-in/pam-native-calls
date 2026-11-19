package dev.pam.calls

import android.app.Activity
import android.os.Bundle
import android.view.View

/** Host Activity of the picture-in-picture tests (declares supportsPictureInPicture). */
class PipHostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(View(this))
    }
}
