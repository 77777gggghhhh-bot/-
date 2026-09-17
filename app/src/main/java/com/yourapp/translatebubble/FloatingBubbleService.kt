package com.yourapp.translatebubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs

class FloatingBubbleService : Service() {

    companion object {
        private const val CHANNEL_ID = "translate_bubble_channel"
        private const val NOTIF_ID = 1001
        private const val CLICK_DRAG_THRESHOLD = 12
        private const val LONG_PRESS_MS = 600L
        private const val MAX_BLOCKS = 60
        private const val PREFS_NAME = "bubble_prefs"
        private const val PREF_X = "bubble_x"
        private const val PREF_Y = "bubble_y"
        private const val PREF_TEXT_SIZE_LEVEL = "text_size_level" // 0=small,1=medium,2=large
        private const val PREF_LANGUAGE_MODE = "language_mode"     // 0=auto,1=force ar->en,2=force en->ar
        const val ACTION_STOP = "com.yourapp.translatebubble.ACTION_STOP"
        const val ACTION_HIDE_OVERLAY = "com.yourapp.translatebubble.ACTION_HIDE_OVERLAY"

        // Bubble colors for each state, so the user can tell what's happening
        // just by looking at the bubble (no need to read a toast).
        private const val COLOR_IDLE = "#3F51B5"       // blue: ready, nothing translated yet
        private const val COLOR_TRANSLATING = "#FFA000" // orange: working right now
        private const val COLOR_ACTIVE = "#43A047"      // green: translation is showing on screen
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams

    // Each translated block is its OWN small overlay window positioned
    // exactly over the original text, so it can be dragged independently.
    private val translatedLabelViews = mutableListOf<Pair<View, WindowManager.LayoutParams>>()

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val translatorHelper = TranslatorHelper()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(VibratorManager::class.java)
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private var overlayIsShowing = false
    private var isTranslating = false

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_STOP -> stopSelf()
                ACTION_HIDE_OVERLAY -> {
                    removeWordOverlay()
                    setBubbleColor(COLOR_IDLE)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundWithNotification()
        addBubble()
        maybeRequestIgnoreBatteryOptimizations()

        val filter = IntentFilter().apply {
            addAction(ACTION_STOP)
            addAction(ACTION_HIDE_OVERLAY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(actionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(actionReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(actionReceiver) }
        removeWordOverlay()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        translatorHelper.close()
        serviceJob.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_HIDE_OVERLAY -> {
                removeWordOverlay()
                setBubbleColor(COLOR_IDLE)
            }
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

        val hideIntent = Intent(ACTION_HIDE_OVERLAY).setPackage(packageName)
        val hidePendingIntent = PendingIntent.getBroadcast(
            this, 1, hideIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Translate Bubble is running")
            .setContentText("Tap bubble to translate; tap again to hide. Stop closes the bubble.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hide", hidePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()

        startForeground(NOTIF_ID, notification)
    }

    // ---------------------------------------------------------------------
    // Ask the system not to kill this service to save battery. Without
    // this, aggressive battery managers can silently stop the bubble in
    // the background. Requires the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
    // permission in the manifest. Safe to call repeatedly - it's a no-op
    // once granted.
    // ---------------------------------------------------------------------
    private fun maybeRequestIgnoreBatteryOptimizations() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { startActivity(intent) }
        }
    }

    // ---------------------------------------------------------------------
    // Haptics: one short tick so the user feels the tap was registered.
    // ---------------------------------------------------------------------
    private fun vibrateTick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(35)
        }
    }

    private fun setBubbleColor(hex: String) {
        (bubbleView as? ImageView)?.setBackgroundColor(Color.parseColor(hex))
    }

    // ---------------------------------------------------------------------
    // Remember where the bubble was left, so it reopens in the same spot
    // instead of resetting to the top every time.
    // ---------------------------------------------------------------------
    private fun savedBubblePosition(): Pair<Int, Int> {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getInt(PREF_X, 0) to prefs.getInt(PREF_Y, 300)
    }

    private fun saveBubblePosition(x: Int, y: Int) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putInt(PREF_X, x)
            .putInt(PREF_Y, y)
            .apply()
    }

    private fun addBubble() {
        val bubble = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            setBackgroundColor(Color.parseColor(COLOR_IDLE))
            setPadding(24, 24, 24, 24)
        }
        bubbleView = bubble

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val (savedX, savedY) = savedBubblePosition()
        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
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
        var wasDragged = false
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
                    wasDragged = false
                    mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > CLICK_DRAG_THRESHOLD || abs(dy) > CLICK_DRAG_THRESHOLD) {
                        mainHandler.removeCallbacks(longPressRunnable)
                        wasDragged = true
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
                            vibrateTick() // feel the tap immediately, before any work happens
                            onBubbleClicked()
                        } else if (wasDragged) {
                            snapBubbleToNearestEdge()
                        }
                    }
                    saveBubblePosition(bubbleParams.x, bubbleParams.y)
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

    // Snap the bubble to whichever screen edge (left/right) it's closer to,
    // like Messenger's chat heads.
    private fun snapBubbleToNearestEdge() {
        val screenWidth = resources.displayMetrics.widthPixels
        val bubbleWidth = bubbleView?.width?.takeIf { it > 0 } ?: dpToPx(56)
        val bubbleCenter = bubbleParams.x + bubbleWidth / 2
        bubbleParams.x = if (bubbleCenter < screenWidth / 2) {
            0
        } else {
            screenWidth - bubbleWidth
        }
        runCatching { windowManager.updateViewLayout(bubbleView, bubbleParams) }
    }

    // ---------------------------------------------------------------------
    // Blend-in backgrounds: capture the screen once per translate, then
    // sample the real pixel color behind each text block instead of using
    // a flat white box. This is what makes the translation look like it
    // replaced the original word in place (Google Lens-style) rather than
    // sitting in an obvious rectangle.
    // ---------------------------------------------------------------------
    private suspend fun captureScreenshotOrNull(
        accessibilityService: TranslationAccessibilityService
    ): Bitmap? = suspendCancellableCoroutine { cont ->
        accessibilityService.captureScreenshot { bitmap ->
            if (cont.isActive) cont.resume(bitmap)
        }
    }

    private fun sampleBackgroundColor(bitmap: Bitmap?, bounds: android.graphics.Rect): Int {
        if (bitmap == null) return Color.WHITE
        val left = bounds.left.coerceIn(0, bitmap.width - 1)
        val top = bounds.top.coerceIn(0, bitmap.height - 1)
        val right = bounds.right.coerceIn(left + 1, bitmap.width)
        val bottom = bounds.bottom.coerceIn(top + 1, bitmap.height)

        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var count = 0
        // Sample the edge pixels of the block (the surrounding background),
        // stepping a few pixels at a time for speed - we don't need every
        // pixel, just a good average.
        val step = 4
        var x = left
        while (x < right) {
            rSum += Color.red(bitmap.getPixel(x, top)); gSum += Color.green(bitmap.getPixel(x, top)); bSum += Color.blue(bitmap.getPixel(x, top)); count++
            x += step
        }
        if (count == 0) return Color.WHITE
        return Color.rgb((rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt())
    }

    // Pick black or white text for readable contrast against a sampled
    // background color (standard relative-luminance check).
    private fun readableTextColorFor(backgroundColor: Int): Int {
        val luminance = (0.299 * Color.red(backgroundColor) +
            0.587 * Color.green(backgroundColor) +
            0.114 * Color.blue(backgroundColor)) / 255
        return if (luminance > 0.6) Color.BLACK else Color.WHITE
    }

    private fun onLongPressStop() {
        removeWordOverlay()
        stopSelf()
    }

    // ---------------------------------------------------------------------
    // Preferences set from MainActivity: text size level and language mode.
    // ---------------------------------------------------------------------
    private fun textSizeScale(): Float {
        val level = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_TEXT_SIZE_LEVEL, 1)
        return when (level) {
            0 -> 0.85f  // small
            2 -> 1.3f   // large
            else -> 1f  // medium (default)
        }
    }

    // null = auto-detect per block; true = force Arabic->English; false = force English->Arabic
    private fun forcedLanguageDirection(): Boolean? {
        return when (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_LANGUAGE_MODE, 0)) {
            1 -> true
            2 -> false
            else -> null
        }
    }

    // ---------------------------------------------------------------------
    // Tap bubble = TOGGLE: if a translation is already showing, hide it
    // (this is how you manually stop a translation). Otherwise translate
    // what's currently visible. If the accessibility service has been
    // killed by the OS in the background (common on some phone brands),
    // jump straight to the Accessibility settings screen instead of just
    // showing a toast, since re-enabling it there is the actual fix.
    // ---------------------------------------------------------------------

    private fun onBubbleClicked() {
        if (overlayIsShowing) {
            removeWordOverlay()
            setBubbleColor(COLOR_IDLE)
            return
        }
        if (isTranslating) {
            Toast.makeText(this, "Still translating\u2026", Toast.LENGTH_SHORT).show()
            return
        }

        val accessibilityService = TranslationAccessibilityService.instance
        if (accessibilityService == null) {
            Toast.makeText(
                this,
                "Accessibility service was turned off (often by battery settings) - re-enabling it now",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }

        val blocks = accessibilityService.extractVisibleText()
        if (blocks.isEmpty()) {
            Toast.makeText(this, "No visible text found on screen", Toast.LENGTH_SHORT).show()
            return
        }

        isTranslating = true
        setBubbleColor(COLOR_TRANSLATING)
        Toast.makeText(this, "Translating\u2026", Toast.LENGTH_SHORT).show()

        val limited = blocks.take(MAX_BLOCKS)
        val forcedDirection = forcedLanguageDirection()

        serviceScope.launch {
            // A hard ceiling on the whole translate flow: if anything hangs
            // (screenshot capture is known to occasionally never call back
            // on some devices, or ML Kit stalls), this guarantees the
            // bubble comes back to a usable state instead of getting stuck
            // showing "Translating..." forever.
            val didShow = withTimeoutOrNull(20_000L) {
                // Capture the screen once now (before any overlay is drawn
                // on top of it) so we can sample real background colors
                // per block. Its own short timeout protects against the
                // screenshot callback never firing.
                val screenshot = withTimeoutOrNull(2_000L) {
                    captureScreenshotOrNull(accessibilityService)
                }

                // Each block's own text decides its own translation
                // direction (unless the user forced one in settings) - see
                // TranslatorHelper.translateBatch for why this matters on
                // mixed-language screens.
                val results = translatorHelper.translateBatch(
                    texts = limited.map { it.text },
                    forcedDirection = forcedDirection
                )

                val translatedBlocks = limited.zip(results).mapNotNull { (block, result) ->
                    result.getOrNull()?.let { translated -> block to translated }
                }

                if (translatedBlocks.isNotEmpty()) {
                    showWordOverlay(translatedBlocks, screenshot)
                    true
                } else {
                    false
                }
            }

            isTranslating = false
            if (didShow == true) {
                setBubbleColor(COLOR_ACTIVE)
            } else {
                val message = if (didShow == null) "Translation timed out" else "Translation failed"
                Toast.makeText(this@FloatingBubbleService, message, Toast.LENGTH_SHORT).show()
                setBubbleColor(COLOR_IDLE)
            }
        }
    }

    // ---------------------------------------------------------------------
    // In-place overlay: one small independent window PER translated block,
    // placed exactly over that block's original position with a background
    // color sampled from the real screen behind it (so it blends in like
    // Google Lens, not a plain white box), and individually draggable.
    // Because each window only covers its own text (not the full screen),
    // the empty space between them is untouched and taps still reach the
    // app underneath normally.
    // ---------------------------------------------------------------------

    private fun showWordOverlay(items: List<Pair<ScreenTextBlock, String>>, screenshot: Bitmap?) {
        removeWordOverlay()

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val scale = textSizeScale()
        for ((block, translated) in items) {
            val backgroundColor = sampleBackgroundColor(screenshot, block.bounds)
            val label = TextView(this).apply {
                text = translated
                setTextColor(readableTextColorFor(backgroundColor))
                setBackgroundColor(backgroundColor) // blends into the real background instead of a plain white box
                textSize = autoTextSizeSp(translated) * scale
                setPadding(6, 2, 6, 2)
                maxLines = 4
            }

            val width = block.bounds.width().coerceAtLeast(dpToPx(20))
            val params = WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = block.bounds.left
                y = block.bounds.top
            }

            windowManager.addView(label, params)
            attachLabelTouchListener(label, params)
            translatedLabelViews.add(label to params)
        }

        overlayIsShowing = true
    }

    // Shrink text a bit when the translation is noticeably longer than the
    // original word/phrase would normally hold, so it's less likely to
    // overflow or wrap excessively.
    private fun autoTextSizeSp(translated: String): Float = when {
        translated.length > 80 -> 9f
        translated.length > 40 -> 10f
        else -> 12f
    }

    // Drag to move an individual translated label, or long-press to copy
    // its text to the clipboard.
    private fun attachLabelTouchListener(label: TextView, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var longPressTriggered = false
        val longPressRunnable = Runnable {
            longPressTriggered = true
            copyLabelText(label)
        }

        label.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
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
                    params.x = initialX + dx
                    params.y = initialY + dy
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }
    }

    private fun copyLabelText(label: TextView) {
        vibrateTick()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Translation", label.text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun removeWordOverlay() {
        translatedLabelViews.forEach { (view, _) ->
            runCatching { windowManager.removeView(view) }
        }
        translatedLabelViews.clear()
        overlayIsShowing = false
    }

    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
    ).toInt()
}
