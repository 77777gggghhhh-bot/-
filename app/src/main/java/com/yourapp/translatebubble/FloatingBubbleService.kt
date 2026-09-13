package com.yourapp.translatebubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class FloatingBubbleService : Service() {

    companion object {
        private const val CHANNEL_ID = "translate_bubble_channel"
        private const val NOTIF_ID = 1001
        private const val CLICK_DRAG_THRESHOLD = 12
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var resultView: View? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val translatorHelper = TranslatorHelper()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundWithNotification()
        addBubble()
    }

    override fun onDestroy() {
        super.onDestroy()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        resultView?.let { runCatching { windowManager.removeView(it) } }
        translatorHelper.close()
        serviceJob.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Translation Bubble",
                NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Translate Bubble is running")
            .setContentText("Tap the bubble to translate visible text")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        startForeground(NOTIF_ID, notification)
    }

    private fun addBubble() {
        val bubble = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            setBackgroundColor(Color.parseColor("#3F51B5"))
            setPadding(24, 24, 24, 24)
        }
        bubbleView = bubble

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 300
        }

        windowManager.addView(bubbleView, bubbleParams)
        attachDragAndClickListener(bubble)
    }

    private fun attachDragAndClickListener(bubble: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = bubbleParams.x
                    initialY = bubbleParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    bubbleParams.x = initialX + dx
                    bubbleParams.y = initialY + dy
                    windowManager.updateViewLayout(bubbleView, bubbleParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = abs(event.rawX - initialTouchX)
                    val dy = abs(event.rawY - initialTouchY)
                    val isTap = dx < CLICK_DRAG_THRESHOLD && dy < CLICK_DRAG_THRESHOLD
                    if (isTap) {
                        onBubbleClicked()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onBubbleClicked() {
        val accessibilityService = TranslationAccessibilityService.instance
        if (accessibilityService == null) {
            showResultOverlay(
                "Accessibility service is not enabled.\nPlease enable it in Settings > Accessibility."
            )
            return
        }

        val extracted = accessibilityService.extractVisibleTextAsString()
        if (extracted.isBlank()) {
            showResultOverlay("No visible text found on screen.")
            return
        }

        showResultOverlay("Translating...")

        serviceScope.launch {
            val translateArToEn = translatorHelper.looksArabic(extracted)
            val result = withContext(Dispatchers.IO) {
                translatorHelper.translate(extracted, arabicToEnglish = translateArToEn)
            }
            result.onSuccess { translatedText ->
                showResultOverlay(translatedText)
            }.onFailure { error ->
                showResultOverlay("Translation failed: ${error.message}")
            }
        }
    }

    private fun showResultOverlay(text: String) {
        removeResultOverlay()

        val padding = dpToPx(16)
        val textView = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(padding, padding, padding, padding)
        }
        val scrollView = ScrollView(this).apply {
            addView(textView)
        }
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#DD212121"))
            addView(scrollView)
            setOnClickListener { removeResultOverlay() }
        }
        resultView = container

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.85).toInt(),
            (resources.displayMetrics.heightPixels * 0.35).toInt(),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleParams.x
            y = (bubbleParams.y - (resources.displayMetrics.heightPixels * 0.37)).toInt()
                .coerceAtLeast(50)
        }

        windowManager.addView(resultView, params)
    }

    private fun removeResultOverlay() {
        resultView?.let {
            runCatching { windowManager.removeView(it) }
            resultView = null
        }
    }

    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
    ).toInt()
}
