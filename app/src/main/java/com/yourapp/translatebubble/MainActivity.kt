package com.yourapp.translatebubble

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

// GitHub dark-theme palette
private object GitHubColors {
    const val BACKGROUND = "#0D1117"
    const val SURFACE = "#161B22"
    const val BORDER = "#30363D"
    const val TEXT_PRIMARY = "#C9D1D9"
    const val TEXT_SECONDARY = "#8B949E"
    const val ACCENT_GREEN = "#238636"      // primary action buttons (like "Code")
    const val ACCENT_GREEN_PRESSED = "#2EA043"
    const val ACCENT_BLUE = "#58A6FF"       // links / info
    const val DANGER_RED = "#F85149"
}

class MainActivity : Activity() {

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("crash_log", Context.MODE_PRIVATE)
        val lastCrash = prefs.getString("last_crash", null)

        if (lastCrash != null) {
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 96, 32, 32)
                setBackgroundColor(Color.parseColor(GitHubColors.BACKGROUND))
            }
            val label = TextView(this).apply {
                text = "Last crash:"
                setTextColor(Color.parseColor(GitHubColors.TEXT_PRIMARY))
                textSize = 16f
                setPadding(0, 0, 0, 16)
            }
            val crashText = TextView(this).apply {
                text = lastCrash
                textSize = 11f
                setTextColor(Color.parseColor(GitHubColors.TEXT_SECONDARY))
                typeface = android.graphics.Typeface.MONOSPACE
            }
            val scroll = ScrollView(this).apply {
                addView(crashText)
                setBackgroundColor(Color.parseColor(GitHubColors.SURFACE))
                setPadding(24, 24, 24, 24)
            }
            val clearButton = styledButton("Clear and continue", accent = GitHubColors.DANGER_RED) {
                prefs.edit().remove("last_crash").apply()
                recreate()
            }
            root.addView(label)
            root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                bottomMargin = 24
            })
            root.addView(clearButton)
            setContentView(root)
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            setBackgroundColor(Color.parseColor(GitHubColors.BACKGROUND))
        }

        val status = TextView(this).apply {
            setTextColor(Color.parseColor(GitHubColors.TEXT_PRIMARY))
            textSize = 14f
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor(GitHubColors.SURFACE))
        }

        val overlayButton = styledButton("1. Grant Overlay Permission") { requestOverlayPermission() }
        val accessibilityButton = styledButton("2. Enable Accessibility Service") { openAccessibilitySettings() }
        val startButton = styledButton("3. Start Floating Bubble", accent = GitHubColors.ACCENT_GREEN) {
            startBubbleServiceIfReady()
        }

        root.addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 32 })
        root.addView(overlayButton)
        root.addView(spacer())
        root.addView(accessibilityButton)
        root.addView(spacer())
        root.addView(startButton)
        setContentView(root)

        statusView = status
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::statusView.isInitialized) refreshStatus()
    }

    // ---------------------------------------------------------------------
    // GitHub-style button: rounded corners, dark border, green accent for
    // primary actions - matches github.com's "Code" / "Merge" button look.
    // ---------------------------------------------------------------------
    private fun styledButton(
        label: String,
        accent: String = GitHubColors.ACCENT_GREEN,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(32, 24, 32, 24)
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(Color.parseColor(accent))
                setStroke(2, Color.parseColor(GitHubColors.BORDER))
            }
            stateListAnimator = null // flat, GitHub-style (no default Material elevation shadow)
            setOnClickListener { onClick() }
        }
    }

    private fun spacer(): android.view.View = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 20
        )
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
        androidx_startForeground(intent)
        Toast.makeText(this, "Bubble started", Toast.LENGTH_SHORT).show()
    }

    private fun androidx_startForeground(intent: Intent) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
