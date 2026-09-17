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
        val autoStartButton = styledButton("4. Allow Auto-start / Background", accent = GitHubColors.ACCENT_BLUE) {
            openAutoStartSettings()
        }
        val shareButton = styledButton("Share with friends", accent = GitHubColors.ACCENT_BLUE) {
            shareApp()
        }

        val bubblePrefs = getSharedPreferences("bubble_prefs", Context.MODE_PRIVATE)

        val textSizeLabels = arrayOf("Small", "Medium", "Large")
        lateinit var textSizeButton: Button
        textSizeButton = styledButton(
            "Text size: ${textSizeLabels[bubblePrefs.getInt("text_size_level", 1)]}",
            accent = GitHubColors.SURFACE
        ) {
            val next = (bubblePrefs.getInt("text_size_level", 1) + 1) % 3
            bubblePrefs.edit().putInt("text_size_level", next).apply()
            textSizeButton.text = "Text size: ${textSizeLabels[next]}"
        }

        val languageLabels = arrayOf("Language: Auto-detect", "Language: Arabic \u2192 English", "Language: English \u2192 Arabic")
        lateinit var languageButton: Button
        languageButton = styledButton(
            languageLabels[bubblePrefs.getInt("language_mode", 0)],
            accent = GitHubColors.SURFACE
        ) {
            val next = (bubblePrefs.getInt("language_mode", 0) + 1) % 3
            bubblePrefs.edit().putInt("language_mode", next).apply()
            languageButton.text = languageLabels[next]
        }

        root.addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 32 })
        root.addView(overlayButton)
        root.addView(spacer())
        root.addView(accessibilityButton)
        root.addView(spacer())
        root.addView(startButton)
        root.addView(spacer())
        root.addView(autoStartButton)
        root.addView(spacer())
        root.addView(shareButton)
        root.addView(spacer())
        root.addView(textSizeButton)
        root.addView(spacer())
        root.addView(languageButton)
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

    // ---------------------------------------------------------------------
    // Share the app with friends via the normal Android share sheet
    // (WhatsApp, Telegram, SMS, etc.). Since this isn't on the Play Store,
    // we share the direct download link to the latest built APK - the
    // build workflow always publishes it at this same "latest-build"
    // release, so the link never goes stale between builds.
    // ---------------------------------------------------------------------
    private fun shareApp() {
        val downloadUrl = "https://github.com/77777gggghhhh-bot/-/releases/download/latest-build/app-debug.apk"
        val message = "جرب تطبيق ترجمة فقاعة - يترجم أي شاشة فورًا!\n$downloadUrl"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }
        startActivity(Intent.createChooser(intent, "Share Translate Bubble"))
    }

    // ---------------------------------------------------------------------
    // Many Chinese Android brands (Oppo/ColorOS, Xiaomi/MIUI, Vivo, Huawei)
    // have their own extra "Auto-start" / background-activity permission
    // screen beyond stock Android's battery optimization setting - without
    // it, the system silently kills background services like ours. There's
    // no single official API for this, so we try each known manufacturer
    // screen and fall back to the app's own settings page if none exist on
    // this device/ROM version.
    // ---------------------------------------------------------------------
    private fun openAutoStartSettings() {
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        val candidates = when {
            manufacturer.contains("oppo") -> listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity"
            )
            manufacturer.contains("xiaomi") -> listOf(
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            manufacturer.contains("vivo") -> listOf(
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
            )
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
            else -> emptyList()
        }

        for ((pkg, cls) in candidates) {
            try {
                val intent = Intent().apply {
                    component = android.content.ComponentName(pkg, cls)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                Toast.makeText(
                    this,
                    "Find \"Translate Bubble\" in this list and allow it",
                    Toast.LENGTH_LONG
                ).show()
                return
            } catch (e: Exception) {
                // Try the next known screen for this manufacturer.
            }
        }

        // No matching OEM screen (or a brand without one, e.g. Samsung/Pixel):
        // fall back to this app's own settings page, plus the standard
        // battery-optimization exemption prompt.
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Toast.makeText(
                this,
                "Look for \"Battery\" or \"Auto-start\" in this app's settings and allow background activity",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open settings on this device", Toast.LENGTH_SHORT).show()
        }
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
