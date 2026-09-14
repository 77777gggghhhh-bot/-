package com.yourapp.translatebubble

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

data class ScreenTextBlock(
    val text: String,
    val bounds: Rect
)

class TranslationAccessibilityService : AccessibilityService() {

    companion object {
        var instance: TranslationAccessibilityService? = null
            private set

        // Fired whenever the foreground screen/app changes, so whoever is
        // showing a translation overlay (the bubble service) knows to
        // clear it - an overlay positioned for the previous screen would
        // otherwise sit in the wrong place over the new one.
        var onScreenChanged: (() -> Unit)? = null
    }

    private fun log(message: String) {
        try {
            val prefs = getSharedPreferences("crash_log", Context.MODE_PRIVATE)
            val existing = prefs.getString("last_crash", "") ?: ""
            prefs.edit().putString("last_crash", "$existing\n[${System.currentTimeMillis()}] $message").commit()
        } catch (e: Exception) { }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        log("Service CONNECTED")
    }

    override fun onDestroy() {
        super.onDestroy()
        log("Service DESTROYED")
        instance = null
    }

    override fun onInterrupt() {
        log("Service INTERRUPTED")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            onScreenChanged?.invoke()
        }
    }

    fun extractVisibleText(): List<ScreenTextBlock> {
        val root = rootInActiveWindow ?: return emptyList()
        val results = mutableListOf<ScreenTextBlock>()
        collectText(root, results)
        return results
    }

    fun extractVisibleTextAsString(): String {
        return extractVisibleText().joinToString(separator = "\n") { it.text }
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: MutableList<ScreenTextBlock>) {
        if (node == null) return
        if (!node.isVisibleToUser) {
            recycleChildren(node)
            return
        }
        val nodeText = node.text?.toString()?.trim()
        val contentDesc = node.contentDescription?.toString()?.trim()
        val combined = when {
            !nodeText.isNullOrEmpty() -> nodeText
            !contentDesc.isNullOrEmpty() -> contentDesc
            else -> null
        }
        if (!combined.isNullOrEmpty()) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                out.add(ScreenTextBlock(combined, bounds))
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            collectText(child, out)
            child?.recycle()
        }
    }

    private fun recycleChildren(node: AccessibilityNodeInfo) {
        for (i in 0 until node.childCount) {
            node.getChild(i)?.recycle()
        }
    }
}
