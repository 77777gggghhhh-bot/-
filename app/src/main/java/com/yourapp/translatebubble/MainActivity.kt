package com.yourapp.translatebubble

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        val status = TextView(this)
        val overlayButton = Button(this).apply {
            text = "1. Grant Overlay Permission"
            setOnClickListener { requestOverlayPermission() }
        }
        val accessibilityButton = Button(this).apply {
            text = "2. Enable Accessibility Service"
            setOnClickListener { openAccessibilitySettings() }
        }
        val startButton = Button(this).apply {
            text = "3. Start Floating Bubble"
            setOnClickListener { startBubbleServiceIfReady() }
        }

        root.addView(status)
        root.addView(overlayButton)
        root.addView(accessibilityButton)
        root.addView(startButton)
        setContentView(root)

        statusView = status
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val overlayGranted = Settings.canDrawOverlays(this)
        val accessibilityGranted = isAccessibilityServiceEnabled()
        statusView.text = "Overlay permission: ${if (overlayGranted) "OK" else "NO"}\n" +
            "Accessibility service: ${if (accessibilityGranted) "OK" else "NO"}"
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            Toast.makeText(this, "Overlay permission already granted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = "$packageName/${TranslationAccessibilityService::class.java.name}"
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            if (colonSplitter.next().equals(expectedComponentName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun startBubbleServiceIfReady() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant overlay permission first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Enable the accessibility service first", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, FloatingBubbleService::class.java)
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Bubble started", Toast.LENGTH_SHORT).show()
    }
}
