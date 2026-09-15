
package com.example.diskwalaautomator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var logText: TextView

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val line = intent.getStringExtra("line") ?: return
            logText.append("\n$line")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("diskwala_automator", MODE_PRIVATE)
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        logText.movementMethod = ScrollingMovementMethod()

        findViewById<Button>(R.id.enableServiceButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.recordButton).setOnClickListener {
            prefs.edit().putString("mode", "recording").apply()
            statusText.text = "Mode: RECORDING — do one full video download in Diskwala now"
        }

        findViewById<Button>(R.id.playButton).setOnClickListener {
            prefs.edit().putString("mode", "playing").apply()
            statusText.text = "Mode: PLAYING — switch to Diskwala"
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            prefs.edit().putString("mode", "idle").apply()
            statusText.text = "Mode: idle"
        }
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(logReceiver, IntentFilter("diskwala_automator_log"))
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(logReceiver)
    }
}
