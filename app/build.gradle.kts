cat > app/src/main/java/com/yourapp/translatebubble/TranslationAccessibilityService.kt << 'EOF'
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
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        EOF
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yourapp.translatebubble"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yourapp.translatebubble"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.android.billingclient:billing-ktx:7.1.1")
}
