package com.yourapp.translatebubble

import android.accessibilityservice.AccessibilityService
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
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

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
