package com.yourapp.translatebubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
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
        private const val LONG_PRESS_MS = 600L
        private const val MAX_BLOCKS = 60
        const val ACTION_STOP = "com.yourapp.translatebubble.ACTION_STOP"
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var overlayContainer: FrameLayout? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val translatorHelper = TranslatorHelper()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayIsShowing = false
    private var isTranslating = false

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundWithNotification()
        addBubble()

        val filter = IntentFilter(ACTION_STOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stopReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(stopReceiver) }
        removeWordOverlay()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        translatorHelper.close()
        serviceJob.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
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

        val stopIntent = Intent(ACTION_STOP).setPackage(packageName)
        val stopPendingIntent = PendingIntent.getBroadcast(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Translate Bubble is running")
            .setContentText("Tap bubble to toggle translation. Long-press bubble or tap Stop to close.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
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
        attachDragClickAndLongPressListener(bubble)
    }

    private fun attachDragClickAndLongPressListener(bubble: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var longPressTriggered = false
        val longPressRunnable = Runnable {
            longPressTriggered = true
            onLongPressStop()
        }

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = bubbleParams.x
                    initialY = bubbleParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    longPressTriggered = false
                    mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > CLICK_DRAG_THRESHOLD || abs(dy) > CLICK_DRAG_THRESHOLD) {
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    bubbleParams.x = initialX + dx
                    bubbleParams.y = initialY + dy
                    windowManager.updateViewLayout(bubbleView, bubbleParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!longPressTriggered) {
                        val dx = abs(event.rawX - initialTouchX)
                        val dy = abs(event.rawY - initialTouchY)
                        val isTap = dx < CLICK_DRAG_THRESHOLD && dy < CLICK_DRAG_THRESHOLD
                        if (isTap) {
                            onBubbleClicked()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }
    }

    private fun onLongPressStop() {
        removeWordOverlay()
        stopSelf()
    }

    // ---------------------------------------------------------------------
    // Tap bubble = TOGGLE: if overlay showing, hide it. Otherwise translate
    // every visible text block and show each translation at its own
    // position, in a pass-through (non-touchable) window.
    // ---------------------------------------------------------------------

    private fun onBubbleClicked() {
        if (overlayIsShowing) {
            removeWordOverlay()
            return
        }
        if (isTranslating) return

        val accessibilityService = TranslationAccessibilityService.instance
        if (accessibilityService == null) {
            Toast.makeText(this, "Enable the accessibility service first", Toast.LENGTH_SHORT).show()
            return
        }

        val blocks = accessibilityService.extractVisibleText()
        if (blocks.isEmpty()) {
            Toast.makeText(this, "No visible text found on screen", Toast.LENGTH_SHORT).show()
            return
        }

        isTranslating = true
        Toast.makeText(this, "Translating\u2026", Toast.LENGTH_SHORT).show()

        val limited = blocks.take(MAX_BLOCKS)
        val sampleText = limited.joinToString(" ") { it.text }
        val arToEn = translatorHelper.looksArabic(sampleText)

        serviceScope.launch {
            val translatedBlocks = mutableListOf<Pair<ScreenTextBlock, String>>()
            withContext(Dispatchers.IO) {
                for (block in limited) {
                    val result = translatorHelper.translate(block.text, arabicToEnglish = arToEn)
                    result.onSuccess { translated ->
                        translatedBlocks.add(block to translated)
                    }
                }
            }
            isTranslating = false
            if (translatedBlocks.isEmpty()) {
                Toast.makeText(this@FloatingBubbleService, "Translation failed", Toast.LENGTH_SHORT).show()
            } else {
                showWordOverlay(translatedBlocks)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Word-position overlay: one full-screen NON-TOUCHABLE window holding
    // small translated labels placed exactly over each original text's
    // bounds. FLAG_NOT_TOUCHABLE lets every touch pass straight through
    // to the app underneath, so scrolling/tapping still works normally.
    // ---------------------------------------------------------------------

    private fun showWordOverlay(items: List<Pair<ScreenTextBlock, String>>) {
        removeWordOverlay()

        val container = FrameLayout(this)

        for ((block, translated) in items) {
            val label = TextView(this).apply {
                text = translated
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#CC1A237E"))
                textSize = 11f
                setPadding(6, 2, 6, 2)
                maxLines = 3
            }
            val width = (block.bounds.width()).coerceAtLeast(dpToPx(20))
            val lp = FrameLayout.LayoutParams(
                width,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            lp.leftMargin = block.bounds.left
            lp.topMargin = block.bounds.top
            container.addView(label, lp)
        }

        overlayContainer = container

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        windowManager.addView(overlayContainer, params)
        overlayIsShowing = true
    }

    private fun removeWordOverlay() {
        overlayContainer?.let {
            runCatching { windowManager.removeView(it) }
            overlayContainer = null
        }
        overlayIsShowing = false
    }

    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
    ).toInt()
}
