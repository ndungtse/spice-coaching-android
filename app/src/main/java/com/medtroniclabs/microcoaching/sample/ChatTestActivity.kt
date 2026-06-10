package com.medtroniclabs.microcoaching.sample

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.medtroniclabs.microcoaching.ui.chat.CoachingChatFragment

/**
 * Development-only activity hosting [CoachingChatFragment] for on-device model testing.
 *
 * Accessible via the navigation drawer under "Test AI Chat". This is not part of the
 * CHW-facing coaching flow — it exists purely to test Gemma inference locally without
 * launching the full UC-1 flow.
 */
class ChatTestActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_test)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.fragment_container,
                    CoachingChatFragment.newInstance(
                        patientId = "test-chw-01",
                        systemContext = "You are a coaching assistant for a Community Health " +
                            "Worker in Bangladesh. Answer questions about hypertension, " +
                            "diabetes, and antenatal care briefly and clearly.",
                    ),
                )
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    companion object {
        fun launch(context: Context) =
            context.startActivity(Intent(context, ChatTestActivity::class.java))
    }
}
