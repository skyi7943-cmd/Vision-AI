package com.example.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.example.model.BoardOrientation
import com.example.model.BoardOrientationMode
import com.example.model.ManualCalibrationNormalizedRect
import com.example.model.OverlayArrowCoordinates
import com.example.model.OverlayButtonSymbol
import com.example.model.PieceColor
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Manages the floating ♟ + ⊞ 校准 tactical capsule window, the touch-passthrough move arrow
 * overlay window, and the full-screen interactive in-game chessboard calibration overlay window.
 */
class FloatingOverlayController(
    private val context: Context,
    private val onAnalyzeClicked: () -> Unit,
    private val onClearArrowLongPressed: () -> Unit,
    private val onCalibrateClicked: () -> Unit = {},
    private val onManualCalibrationSaved: (rect: ManualCalibrationNormalizedRect?, analyzeImmediately: Boolean) -> Unit,
    private val onAutoDetectBoardRequested: (((RectF?) -> Unit) -> Unit)? = null,
    private val onOrientationModeChanged: ((BoardOrientationMode) -> Unit)? = null,
    private val onUserColorChanged: ((PieceColor) -> Unit)? = null
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var buttonView: FloatingChessButtonView? = null
    private var buttonParams: WindowManager.LayoutParams? = null

    private var arrowView: MoveArrowOverlayView? = null
    private var arrowParams: WindowManager.LayoutParams? = null

    private var calibrationView: ManualCalibrationOverlayView? = null

    val isShowing: Boolean
        get() = buttonView != null

    val isCalibrationOverlayShowing: Boolean
        get() = calibrationView != null

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    fun showOverlays(hasManualCalibration: Boolean = false) {
        mainHandler.post {
            if (!canDrawOverlays()) return@post
            if (arrowView == null) {
                val av = MoveArrowOverlayView(context)
                val ap = createTouchPassthroughArrowParams()
                av.visibility = View.GONE
                try {
                    windowManager.addView(av, ap)
                    arrowView = av
                    arrowParams = ap
                } catch (_: Exception) {
                }
            }
            if (buttonView == null) {
                val bv = FloatingChessButtonView(
                    context = context,
                    onTapAnalyze = onAnalyzeClicked,
                    onLongPressClear = onClearArrowLongPressed,
                    onTapCalibrate = onCalibrateClicked,
                    onDragBy = { dx, dy -> moveButtonBy(dx, dy) }
                )
                bv.setCalibrationActive(hasManualCalibration)
                val bp = createFloatingButtonParams()
                try {
                    windowManager.addView(bv, bp)
                    buttonView = bv
                    buttonParams = bp
                } catch (_: Exception) {
                }
            } else {
                buttonView?.setCalibrationActive(hasManualCalibration)
            }
        }
    }

    fun hideAndReleaseAll() {
        mainHandler.post {
            calibrationView?.let {
                try {
                    windowManager.removeView(it)
                } catch (_: Exception) {
                }
            }
            calibrationView = null

            arrowView?.let {
                try {
                    windowManager.removeView(it)
                } catch (_: Exception) {
                }
            }
            arrowView = null
            arrowParams = null

            buttonView?.let {
                try {
                    windowManager.removeView(it)
                } catch (_: Exception) {
                }
            }
            buttonView = null
            buttonParams = null
        }
    }

    fun updateButtonState(symbol: OverlayButtonSymbol, badgeText: String? = null) {
        mainHandler.post {
            buttonView?.setButtonState(symbol, badgeText)
        }
    }

    fun updateCalibrationIndicator(hasActiveCalibration: Boolean) {
        mainHandler.post {
            buttonView?.setCalibrationActive(hasActiveCalibration)
        }
    }

    fun showArrow(arrow: OverlayArrowCoordinates) {
        mainHandler.post {
            val av = arrowView ?: return@post
            av.setArrow(arrow)
            if (av.visibility != View.VISIBLE) {
                av.visibility = View.VISIBLE
            }
            av.invalidate()
        }
    }

    /**
     * Immediately clears the move arrow and hides the arrow overlay surface, invoking
     * [onCleared] after the UI thread has committed the visibility & invalidation change
     * and nudging the floating button so `VirtualDisplay` emits a clean post-overlay frame.
     */
    fun clearArrowAndNotifyCommitted(onCleared: (clearedAtNanoTime: Long) -> Unit) {
        mainHandler.post {
            arrowView?.let { av ->
                av.setArrow(null)
                av.visibility = View.GONE
            }
            buttonView?.setButtonState(OverlayButtonSymbol.ANALYZING, null)
            val anchorView = buttonView ?: arrowView
            if (anchorView != null) {
                anchorView.post {
                    val committedNs = System.nanoTime()
                    // Trigger a subtle redraw pulse after 16ms so VirtualDisplay is guaranteed
                    // to produce a new frame even when the underlying chess app is static.
                    buttonView?.TriggerFramePulse()
                    onCleared(committedNs)
                }
            } else {
                onCleared(System.nanoTime())
            }
        }
    }

    fun clearArrowImmediately() {
        mainHandler.post {
            arrowView?.let { av ->
                av.setArrow(null)
                av.visibility = View.GONE
            }
        }
    }

    fun getArrowWindowScreenOffset(): Pair<Int, Int> {
        val av = arrowView ?: return Pair(0, 0)
        val loc = IntArray(2)
        return try {
            av.getLocationOnScreen(loc)
            Pair(loc[0], loc[1])
        } catch (_: Exception) {
            Pair(0, 0)
        }
    }

    /**
     * Opens the full-screen interactive chessboard calibration overlay directly on top of
     * the current game screen. Temporarily hides the floating button and arrow while calibrating
     * so no corner of the chessboard is obscured.
     */
    fun showManualCalibrationOverlay(
        initialCalibration: ManualCalibrationNormalizedRect?,
        screenWidth: Int,
        screenHeight: Int,
        screenRotation: Int,
        initialOrientationMode: BoardOrientationMode = BoardOrientationMode.AUTO,
        initialUserColor: PieceColor = PieceColor.WHITE,
        autoDetectedInitialRectPx: RectF? = null
    ) {
        mainHandler.post {
            if (!canDrawOverlays()) return@post
            if (calibrationView != null) return@post

            // Clear arrow and temporarily hide floating button so the game board is completely unobstructed
            arrowView?.let { av ->
                av.setArrow(null)
                av.visibility = View.GONE
            }
            buttonView?.visibility = View.INVISIBLE

            val cv = ManualCalibrationOverlayView(
                context = context,
                initialRect = initialCalibration,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                screenRotation = screenRotation,
                initialOrientationMode = initialOrientationMode,
                initialUserColor = initialUserColor,
                autoDetectedInitialRectPx = autoDetectedInitialRectPx,
                onConfirm = { rect, analyzeNow ->
                    closeManualCalibrationOverlay()
                    buttonView?.setCalibrationActive(true)
                    buttonView?.setButtonState(OverlayButtonSymbol.READY, "已校准")
                    onManualCalibrationSaved(rect, analyzeNow)
                },
                onResetToAuto = {
                    closeManualCalibrationOverlay()
                    buttonView?.setCalibrationActive(false)
                    buttonView?.setButtonState(OverlayButtonSymbol.READY, "自动")
                    onManualCalibrationSaved(null, false)
                },
                onCancel = {
                    closeManualCalibrationOverlay()
                },
                onRequestAutoDetectBoard = onAutoDetectBoardRequested,
                onOrientationModeChanged = onOrientationModeChanged,
                onUserColorChanged = onUserColorChanged
            )
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            try {
                windowManager.addView(cv, params)
                calibrationView = cv
            } catch (_: Exception) {
                buttonView?.visibility = View.VISIBLE
            }
        }
    }

    fun closeManualCalibrationOverlay() {
        mainHandler.post {
            calibrationView?.let {
                try {
                    windowManager.removeView(it)
                } catch (_: Exception) {
                }
            }
            calibrationView = null
            buttonView?.visibility = View.VISIBLE
        }
    }

    private fun moveButtonBy(dx: Int, dy: Int) {
        val bv = buttonView ?: return
        val bp = buttonParams ?: return
        val dm = context.resources.displayMetrics
        bp.x = (bp.x + dx).coerceIn(0, (dm.widthPixels - bv.width).coerceAtLeast(0))
        bp.y = (bp.y + dy).coerceIn(0, (dm.heightPixels - bv.height).coerceAtLeast(0))
        try {
            windowManager.updateViewLayout(bv, bp)
        } catch (_: Exception) {
        }
    }

    private fun createFloatingButtonParams(): WindowManager.LayoutParams {
        val density = context.resources.displayMetrics.density
        val widthPx = (68 * density).roundToInt()
        val heightPx = (102 * density).roundToInt()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            widthPx,
            heightPx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (14 * density).roundToInt()
            y = (170 * density).roundToInt()
        }
    }

    /**
     * Creates WindowManager.LayoutParams for the transparent move arrow overlay.
     *
     * CRITICAL ANDROID 12+ (API 31+) TOUCH PASSTHROUGH HANDLING:
     * Setting `FLAG_NOT_TOUCHABLE` alone does NOT allow touches to pass through on Android 12+
     * if the window's alpha exceeds `InputManager.maximumObscuringOpacityForTouch` (default 0.8f).
     * We explicitly query `InputManager.maximumObscuringOpacityForTouch` and clamp `alpha` safely
     * below that threshold (`<= 0.72f`) so the underlying chess app remains 100% touchable!
     */
    private fun createTouchPassthroughArrowParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val safeAlpha = computeTouchPassthroughSafeAlpha(context)
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = safeAlpha
        }
    }

    companion object {
        fun computeTouchPassthroughSafeAlpha(context: Context): Float {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
                val maxOpacity = inputManager?.maximumObscuringOpacityForTouch ?: 0.8f
                return min(0.72f, (maxOpacity * 0.9f).coerceIn(0.3f, 0.78f))
            }
            return 0.75f
        }
    }
}

/**
 * Draggable tactical floating capsule view for in-game operation:
 * - Upper circular button (`♟` / `…` / `✓` / `!`):
 *   - Single tap: triggers one-shot board capture + vision + engine analysis.
 *   - Long press: cancels analysis and clears move arrow overlay.
 * - Lower attached pill button (`⊞ 校准` / `⊞ 已校`):
 *   - Single tap: opens the interactive chessboard calibration overlay right inside the game interface!
 * - Dragging anywhere on the capsule moves the floating window smoothly.
 */
@SuppressLint("ViewConstructor")
class FloatingChessButtonView(
    context: Context,
    private val onTapAnalyze: () -> Unit,
    private val onLongPressClear: () -> Unit,
    private val onTapCalibrate: () -> Unit = {},
    private val onDragBy: (dx: Int, dy: Int) -> Unit
) : View(context) {

    private var currentSymbol: OverlayButtonSymbol = OverlayButtonSymbol.READY
    private var badgeText: String? = null
    private var calibrationActive: Boolean = false
    private var pulseToggle: Boolean = false

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()

    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var downInCalibrateButton = false
    private var isDragging = false
    private var longPressTriggered = false

    private val calibratePillRect = RectF()

    private val longPressRunnable = Runnable {
        if (!isDragging && !downInCalibrateButton) {
            longPressTriggered = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onLongPressClear()
        }
    }

    private val capsuleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC090D16")
        style = Paint.Style.FILL
    }
    private val capsuleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4D38BDF8")
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
    }
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 22f * density
        isFakeBoldText = true
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")
        textAlign = Paint.Align.CENTER
        textSize = 9f * density
        isFakeBoldText = true
    }
    private val calibratePillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val calibrateBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val calibrateTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 10.5f * density
        isFakeBoldText = true
    }

    fun setButtonState(symbol: OverlayButtonSymbol, badge: String?) {
        currentSymbol = symbol
        badgeText = badge
        contentDescription = "${symbol.description} | 点击下方按钮校准棋盘"
        invalidate()
    }

    fun setCalibrationActive(active: Boolean) {
        calibrationActive = active
        invalidate()
    }

    fun isCalibrationActive(): Boolean = calibrationActive

    fun TriggerFramePulse() {
        pulseToggle = !pulseToggle
        invalidate()
        postDelayed({
            pulseToggle = !pulseToggle
            invalidate()
        }, 18L)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat().coerceAtLeast(60f * density)
        val h = height.toFloat().coerceAtLeast(94f * density)

        // Outer subtle tactical dock capsule
        val outerRect = RectF(2f * density, 2f * density, w - 2f * density, h - 2f * density)
        canvas.drawRoundRect(outerRect, 20f * density, 20f * density, capsuleBgPaint)
        canvas.drawRoundRect(outerRect, 20f * density, 20f * density, capsuleBorderPaint)

        // Top circular ♟ analysis button
        val cx = w * 0.5f
        val circleRadius = min(w * 0.42f, 26f * density)
        val cy = 6f * density + circleRadius

        bgPaint.color = when (currentSymbol) {
            OverlayButtonSymbol.READY -> Color.parseColor("#F20F172A")
            OverlayButtonSymbol.ANALYZING -> Color.parseColor("#F21E3A8A")
            OverlayButtonSymbol.SUCCESS -> Color.parseColor("#F2064E3B")
            OverlayButtonSymbol.FAILED -> Color.parseColor("#F27F1D1D")
        }
        ringPaint.color = when (currentSymbol) {
            OverlayButtonSymbol.READY -> Color.parseColor("#38BDF8")
            OverlayButtonSymbol.ANALYZING -> Color.parseColor("#FBBF24")
            OverlayButtonSymbol.SUCCESS -> Color.parseColor("#34D399")
            OverlayButtonSymbol.FAILED -> Color.parseColor("#F87171")
        }

        canvas.drawCircle(cx, cy, circleRadius, bgPaint)
        canvas.drawCircle(cx, cy, circleRadius, ringPaint)

        val textYOffset = if (badgeText.isNullOrBlank()) {
            -(symbolPaint.descent() + symbolPaint.ascent()) * 0.5f
        } else {
            -(symbolPaint.descent() + symbolPaint.ascent()) * 0.3f - 3.5f * density
        }
        canvas.drawText(currentSymbol.symbol, cx, cy + textYOffset, symbolPaint)

        badgeText?.takeIf { it.isNotBlank() }?.let { b ->
            canvas.drawText(b.take(6), cx, cy + circleRadius * 0.62f, badgePaint)
        }

        // Bottom in-game "⊞ 校准" (Calibrate Board) button
        val pillTop = cy + circleRadius + 5f * density
        val pillBottom = (h - 5f * density).coerceAtLeast(pillTop + 22f * density)
        calibratePillRect.set(6f * density, pillTop, w - 6f * density, pillBottom)

        if (calibrationActive) {
            calibratePillPaint.color = Color.parseColor("#E6065F46")
            calibrateBorderPaint.color = Color.parseColor("#34D399")
            calibrateTextPaint.color = Color.parseColor("#A7F3D0")
        } else {
            calibratePillPaint.color = Color.parseColor("#E61E293B")
            calibrateBorderPaint.color = Color.parseColor("#F59E0B")
            calibrateTextPaint.color = Color.parseColor("#FDE68A")
        }

        canvas.drawRoundRect(calibratePillRect, 10f * density, 10f * density, calibratePillPaint)
        canvas.drawRoundRect(calibratePillRect, 10f * density, 10f * density, calibrateBorderPaint)

        val calLabel = if (calibrationActive) "⊞ 已校" else "⊞ 校准"
        val calTextY = calibratePillRect.centerY() - (calibrateTextPaint.descent() + calibrateTextPaint.ascent()) * 0.5f
        canvas.drawText(calLabel, calibratePillRect.centerX(), calTextY, calibrateTextPaint)

        // 1px subtle alpha dot to ensure SurfaceFlinger composition update when pulsed
        if (pulseToggle) {
            bgPaint.color = Color.argb(4, 255, 255, 255)
            canvas.drawRect(0f, 0f, 1f, 1f, bgPaint)
        }
    }

    private fun isPointInCalibrateSection(localX: Float, localY: Float): Boolean {
        val h = height.toFloat().coerceAtLeast(94f * density)
        val splitY = if (calibratePillRect.top > 0f) calibratePillRect.top - 2f * density else h * 0.64f
        return localY >= splitY && localX in 0f..width.toFloat().coerceAtLeast(68f * density)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                isDragging = false
                longPressTriggered = false
                downInCalibrateButton = isPointInCalibrateSection(event.x, event.y)
                if (!downInCalibrateButton) {
                    handler?.postDelayed(longPressRunnable, longPressTimeoutMs)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val totalDx = event.rawX - downRawX
                val totalDy = event.rawY - downRawY
                if (!isDragging && hypot(totalDx.toDouble(), totalDy.toDouble()) > touchSlop) {
                    isDragging = true
                    handler?.removeCallbacks(longPressRunnable)
                }
                if (isDragging) {
                    val stepDx = (event.rawX - lastRawX).roundToInt()
                    val stepDy = (event.rawY - lastRawY).roundToInt()
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    onDragBy(stepDx, stepDy)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler?.removeCallbacks(longPressRunnable)
                if (!isDragging && !longPressTriggered) {
                    performClick()
                    if (downInCalibrateButton || isPointInCalibrateSection(event.x, event.y)) {
                        onTapCalibrate()
                    } else {
                        onTapAnalyze()
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler?.removeCallbacks(longPressRunnable)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

/**
 * Transparent non-focusable, non-touchable overlay that draws the recommended move arrow
 * aligned strictly between the start square center and target square center.
 */
class MoveArrowOverlayView(context: Context) : View(context) {

    private var arrowCoords: OverlayArrowCoordinates? = null
    private val density = resources.displayMetrics.density

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D90F172A")
        style = Paint.Style.STROKE
        strokeWidth = 11f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val shaftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981")
        style = Paint.Style.STROKE
        strokeWidth = 6.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val headFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981")
        style = Paint.Style.FILL
    }
    private val headStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D90F172A")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val originRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val promoBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E60F172A")
        style = Paint.Style.FILL
    }
    private val promoTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FBBF24")
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val headPath = Path()

    fun setArrow(coords: OverlayArrowCoordinates?) {
        arrowCoords = coords
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val coords = arrowCoords ?: return

        val x1 = coords.fromX
        val y1 = coords.fromY
        val x2 = coords.toX
        val y2 = coords.toY

        val dx = x2 - x1
        val dy = y2 - y1
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (dist < 4f) return

        val angle = atan2(dy.toDouble(), dx.toDouble()).toFloat()
        val headLength = min(24f * density, dist * 0.38f)
        val headAngle = Math.toRadians(28.0).toFloat()

        // Shorten shaft slightly so it meets the base of the arrowhead cleanly
        val shaftEndX = x2 - cos(angle) * (headLength * 0.65f)
        val shaftEndY = y2 - sin(angle) * (headLength * 0.65f)

        // Draw origin square center ring
        canvas.drawCircle(x1, y1, 8f * density, originRingPaint)

        // Draw shaft outline and main shaft
        canvas.drawLine(x1, y1, shaftEndX, shaftEndY, outlinePaint)
        canvas.drawLine(x1, y1, shaftEndX, shaftEndY, shaftPaint)

        // Draw triangular arrowhead with tip exactly at target square center (x2, y2)
        val wing1X = x2 - headLength * cos(angle - headAngle)
        val wing1Y = y2 - headLength * sin(angle - headAngle)
        val wing2X = x2 - headLength * cos(angle + headAngle)
        val wing2Y = y2 - headLength * sin(angle + headAngle)

        headPath.reset()
        headPath.moveTo(x2, y2)
        headPath.lineTo(wing1X, wing1Y)
        headPath.lineTo(wing2X, wing2Y)
        headPath.close()

        canvas.drawPath(headPath, headStrokePaint)
        canvas.drawPath(headPath, headFillPaint)

        // If promotion move, draw clear promotion badge near target square
        coords.promotionText?.takeIf { it.isNotBlank() }?.let { promo ->
            val textWidth = promoTextPaint.measureText(promo)
            val padH = 8f * density
            val padV = 5f * density
            val boxCy = (y2 - 26f * density).coerceAtLeast(24f * density)
            val rect = RectF(
                x2 - textWidth * 0.5f - padH,
                boxCy - 10f * density - padV,
                x2 + textWidth * 0.5f + padH,
                boxCy + 6f * density + padV
            )
            canvas.drawRoundRect(rect, 8f * density, 8f * density, promoBgPaint)
            canvas.drawText(promo, x2, boxCy + 2f * density, promoTextPaint)
        }
    }
}

/**
 * Full-screen interactive in-game chessboard calibration overlay.
 *
 * Features:
 * - Crystal-clear undimmed cutout inside the 8x8 chessboard box so the game board underneath is 100% visible.
 * - Live 8x8 square grid preview with algebraic coordinate labels (`a`..`h`, `1`..`8`) matching board orientation.
 * - 4-corner handles (Top-Left, Top-Right, Bottom-Left, Bottom-Right) + whole-board interior drag.
 * - `AI自动贴合` button to automatically snap the calibration grid to the detected 8x8 board on the game screen.
 * - `铺满屏宽` button to snap to 100% screen width 1:1 square (ideal for Chess.com & Lichess mobile interfaces).
 * - Quick orientation mode (`自动方向` / `白方在下` / `黑方在下`) and player color (`我执白` / `我执黑`) switches right in the game UI.
 * - Fine-tune nudge controls (`◀` `▲` `▼` `▶` `－` `＋` `铺满宽`) for pixel-exact alignment.
 * - Action bar: `取消`, `恢复自动`, `保存校准`, and `保存并分析`.
 */
@SuppressLint("ViewConstructor")
class ManualCalibrationOverlayView(
    context: Context,
    initialRect: ManualCalibrationNormalizedRect?,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val screenRotation: Int,
    initialOrientationMode: BoardOrientationMode = BoardOrientationMode.AUTO,
    initialUserColor: PieceColor = PieceColor.WHITE,
    autoDetectedInitialRectPx: RectF? = null,
    private val onConfirm: (rect: ManualCalibrationNormalizedRect, analyzeImmediately: Boolean) -> Unit,
    private val onResetToAuto: () -> Unit = {},
    private val onCancel: () -> Unit,
    private val onRequestAutoDetectBoard: (((RectF?) -> Unit) -> Unit)? = null,
    private val onOrientationModeChanged: ((BoardOrientationMode) -> Unit)? = null,
    private val onUserColorChanged: ((PieceColor) -> Unit)? = null
) : View(context) {

    private val density = resources.displayMetrics.density
    private var leftPx: Float
    private var topPx: Float
    private var rightPx: Float
    private var bottomPx: Float

    var lockSquareAspect: Boolean = true
        private set

    var currentOrientationMode: BoardOrientationMode = initialOrientationMode
        private set

    var currentUserColor: PieceColor = initialUserColor
        private set

    private var statusHintText: String = "拖动四角圆点或框内上下平移，使 8×8 网格贴合游戏棋盘"

    private var activeHandle: Int = HANDLE_NONE

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val outsideDimPaint = Paint().apply {
        color = Color.parseColor("#A6020617")
        style = Paint.Style.FILL
    }
    private val hudPanelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E60F172A")
        style = Paint.Style.FILL
    }
    private val hudStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4D38BDF8")
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        style = Paint.Style.STROKE
        strokeWidth = 2.8f * density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B338BDF8")
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }
    private val centerCrossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC10B981")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F59E0B")
        style = Paint.Style.FILL
    }
    private val handleRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val coordTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FDE68A")
        textSize = 10.5f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val chipActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E3A8A")
        style = Paint.Style.FILL
    }
    private val chipInactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E293B")
        style = Paint.Style.FILL
    }
    private val chipAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#065F46")
        style = Paint.Style.FILL
    }
    private val btnConfirmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#059669")
        style = Paint.Style.FILL
    }
    private val btnConfirmAnalyzePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2563EB")
        style = Paint.Style.FILL
    }
    private val btnResetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#475569")
        style = Paint.Style.FILL
    }
    private val btnCancelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DC2626")
        style = Paint.Style.FILL
    }
    private val titleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12.5f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val smallBtnTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // Top bar interactive chip rects
    private val autoSnapChipRect = RectF()
    private val squareLockChipRect = RectF()
    private val orientationChipRect = RectF()
    private val userColorChipRect = RectF()

    // Fine-tune nudge button rects
    private val nudgeLeftRect = RectF()
    private val nudgeUpRect = RectF()
    private val nudgeDownRect = RectF()
    private val nudgeRightRect = RectF()
    private val nudgeShrinkRect = RectF()
    private val nudgeExpandRect = RectF()
    private val fullWidthSnapRect = RectF()

    // Bottom action button rects
    private val cancelButtonRect = RectF()
    private val resetAutoButtonRect = RectF()
    private val confirmButtonRect = RectF()
    private val confirmAndAnalyzeButtonRect = RectF()

    init {
        val w = screenWidth.coerceAtLeast(360).toFloat()
        val h = screenHeight.coerceAtLeast(640).toFloat()
        if (initialRect != null && initialRect.isValidGeometry()) {
            leftPx = initialRect.leftRatio * w
            topPx = initialRect.topRatio * h
            rightPx = initialRect.rightRatio * w
            bottomPx = initialRect.bottomRatio * h
            lockSquareAspect = abs((rightPx - leftPx) - (bottomPx - topPx)) <= 14f * density
            statusHintText = "已加载当前保存的棋盘校准框，可微调或直接点击保存"
        } else if (autoDetectedInitialRectPx != null &&
            autoDetectedInitialRectPx.width() >= 100f &&
            autoDetectedInitialRectPx.height() >= 100f
        ) {
            leftPx = autoDetectedInitialRectPx.left.coerceIn(0f, w - 100f)
            topPx = autoDetectedInitialRectPx.top.coerceIn(0f, h - 100f)
            rightPx = autoDetectedInitialRectPx.right.coerceIn(leftPx + 100f, w)
            bottomPx = autoDetectedInitialRectPx.bottom.coerceIn(topPx + 100f, h)
            lockSquareAspect = abs((rightPx - leftPx) - (bottomPx - topPx)) <= 14f * density
            statusHintText = "✓ 已自动检测并贴合游戏棋盘！确认网格对齐后点击「保存并分析」"
        } else if (h > w * 1.2f) {
            // Portrait mobile phone default: full screen width 1:1 square positioned at Chess.com / Lichess height
            leftPx = 0f
            rightPx = w
            topPx = ((h - w) * 0.567f).coerceIn(0f, h - w)
            bottomPx = topPx + w
            lockSquareAspect = true
            statusHintText = "可上下拖动框内区域或点击「AI自动贴合」对齐游戏棋盘"
        } else {
            val side = min(w, h) * 0.86f
            leftPx = (w - side) * 0.5f
            topPx = (h - side) * 0.5f
            rightPx = leftPx + side
            bottomPx = topPx + side
            lockSquareAspect = true
        }
    }

    fun getCurrentBoardPixelRect(): RectF = RectF(leftPx, topPx, rightPx, bottomPx)

    fun setBoardPixelRect(left: Float, top: Float, right: Float, bottom: Float) {
        val viewW = max(width, screenWidth).coerceAtLeast(320).toFloat()
        val viewH = max(height, screenHeight).coerceAtLeast(480).toFloat()
        val minSize = 120f * density
        leftPx = left.coerceIn(0f, viewW - minSize)
        topPx = top.coerceIn(0f, viewH - minSize)
        rightPx = right.coerceIn(leftPx + minSize, viewW)
        bottomPx = bottom.coerceIn(topPx + minSize, viewH)
        invalidate()
    }

    fun snapToFullScreenWidthSquare() {
        val viewW = max(width, screenWidth).coerceAtLeast(320).toFloat()
        val viewH = max(height, screenHeight).coerceAtLeast(480).toFloat()
        val side = min(viewW, viewH)
        val currentCenterY = (topPx + bottomPx) * 0.5f
        leftPx = 0f
        rightPx = side
        topPx = (currentCenterY - side * 0.5f).coerceIn(0f, (viewH - side).coerceAtLeast(0f))
        bottomPx = topPx + side
        lockSquareAspect = true
        statusHintText = "已铺满屏幕宽度 (1:1 正方形)，上下拖动框内即可对齐棋盘上下沿"
        invalidate()
    }

    fun performSaveCalibration(analyzeImmediately: Boolean) {
        val loc = IntArray(2)
        try {
            getLocationOnScreen(loc)
        } catch (_: Exception) {
        }
        val totalW = max(width + loc[0], screenWidth).coerceAtLeast(1).toFloat()
        val totalH = max(height + loc[1], screenHeight).coerceAtLeast(1).toFloat()
        val normalized = ManualCalibrationNormalizedRect(
            leftRatio = ((leftPx + loc[0]) / totalW).coerceIn(0f, 1f),
            topRatio = ((topPx + loc[1]) / totalH).coerceIn(0f, 1f),
            rightRatio = ((rightPx + loc[0]) / totalW).coerceIn(0f, 1f),
            bottomRatio = ((bottomPx + loc[1]) / totalH).coerceIn(0f, 1f),
            calibratedScreenWidth = totalW.roundToInt(),
            calibratedScreenHeight = totalH.roundToInt(),
            calibratedRotation = screenRotation
        )
        onConfirm(normalized, analyzeImmediately)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = (if (width > 0) width else screenWidth).toFloat()
        val h = (if (height > 0) height else screenHeight).toFloat()

        // 1. Draw 4 outer dim rectangles around the chessboard so the interior remains 100% clear!
        if (topPx > 0f) {
            canvas.drawRect(0f, 0f, w, topPx, outsideDimPaint)
        }
        if (bottomPx < h) {
            canvas.drawRect(0f, bottomPx, w, h, outsideDimPaint)
        }
        if (leftPx > 0f) {
            canvas.drawRect(0f, topPx, leftPx, bottomPx, outsideDimPaint)
        }
        if (rightPx < w) {
            canvas.drawRect(rightPx, topPx, w, bottomPx, outsideDimPaint)
        }

        // 2. Draw 8x8 internal grid preview
        val boardW = rightPx - leftPx
        val boardH = bottomPx - topPx
        val cellW = boardW / 8f
        val cellH = boardH / 8f

        for (i in 1..7) {
            val gx = leftPx + i * cellW
            val gy = topPx + i * cellH
            canvas.drawLine(gx, topPx, gx, bottomPx, gridPaint)
            canvas.drawLine(leftPx, gy, rightPx, gy, gridPaint)
        }
        // Highlight center 4x4 intersection to verify square symmetry
        val centerX = leftPx + 4f * cellW
        val centerY = topPx + 4f * cellH
        canvas.drawLine(centerX - 10f * density, centerY, centerX + 10f * density, centerY, centerCrossPaint)
        canvas.drawLine(centerX, centerY - 10f * density, centerX, centerY + 10f * density, centerCrossPaint)

        // Outer border
        canvas.drawRect(leftPx, topPx, rightPx, bottomPx, borderPaint)

        // 3. Draw algebraic coordinate labels (files a..h & ranks 1..8) inside edge squares
        coordTextPaint.textSize = 10.5f * density
        val previewOrientation = when (currentOrientationMode) {
            BoardOrientationMode.BLACK_BOTTOM -> BoardOrientation.BLACK_BOTTOM
            else -> BoardOrientation.WHITE_BOTTOM
        }
        for (c in 0..7) {
            val fileChar = if (previewOrientation == BoardOrientation.WHITE_BOTTOM) {
                ('a' + c)
            } else {
                ('h' - c)
            }
            val cx = leftPx + (c + 0.5f) * cellW
            val cy = bottomPx - 6f * density
            canvas.drawText(fileChar.toString(), cx, cy, coordTextPaint)
        }
        for (r in 0..7) {
            val rankChar = if (previewOrientation == BoardOrientation.WHITE_BOTTOM) {
                ('8' - r)
            } else {
                ('1' + r)
            }
            val cx = leftPx + 8f * density
            val cy = topPx + (r + 0.5f) * cellH + 4f * density
            canvas.drawText(rankChar.toString(), cx, cy, coordTextPaint)
        }

        // 4. Draw 4 corner handles (Top-Left, Top-Right, Bottom-Left, Bottom-Right)
        val handleR = 12.5f * density
        drawCornerHandle(canvas, leftPx, topPx, handleR)
        drawCornerHandle(canvas, rightPx, topPx, handleR)
        drawCornerHandle(canvas, leftPx, bottomPx, handleR)
        drawCornerHandle(canvas, rightPx, bottomPx, handleR)

        // 5. Draw Top HUD Control Panel
        val topPanelTop = 34f * density
        val topPanelRect = RectF(10f * density, topPanelTop, w - 10f * density, topPanelTop + 86f * density)
        canvas.drawRoundRect(topPanelRect, 12f * density, 12f * density, hudPanelPaint)
        canvas.drawRoundRect(topPanelRect, 12f * density, 12f * density, hudStrokePaint)

        canvas.drawText(
            statusHintText,
            w * 0.5f,
            topPanelTop + 20f * density,
            titleTextPaint
        )

        val chipY = topPanelTop + 30f * density
        val chipH = 32f * density
        val gap = 6f * density
        val totalChipW = w - 32f * density
        val chipW = (totalChipW - gap * 3f) / 4f

        autoSnapChipRect.set(16f * density, chipY, 16f * density + chipW, chipY + chipH)
        squareLockChipRect.set(autoSnapChipRect.right + gap, chipY, autoSnapChipRect.right + gap + chipW, chipY + chipH)
        orientationChipRect.set(squareLockChipRect.right + gap, chipY, squareLockChipRect.right + gap + chipW, chipY + chipH)
        userColorChipRect.set(orientationChipRect.right + gap, chipY, orientationChipRect.right + gap + chipW, chipY + chipH)

        drawChipButton(canvas, autoSnapChipRect, "AI自动贴合", chipAccentPaint)
        drawChipButton(
            canvas,
            squareLockChipRect,
            if (lockSquareAspect) "1:1正方:开" else "1:1正方:关",
            if (lockSquareAspect) chipActivePaint else chipInactivePaint
        )
        val orientShort = when (currentOrientationMode) {
            BoardOrientationMode.AUTO -> "方向:自动"
            BoardOrientationMode.WHITE_BOTTOM -> "方向:白下"
            BoardOrientationMode.BLACK_BOTTOM -> "方向:黑下"
        }
        drawChipButton(canvas, orientationChipRect, orientShort, chipActivePaint)
        val colorShort = if (currentUserColor == PieceColor.WHITE) "执棋:白棋" else "执棋:黑棋"
        drawChipButton(canvas, userColorChipRect, colorShort, chipActivePaint)

        // Dimension readout
        coordTextPaint.textSize = 10f * density
        canvas.drawText(
            "当前框选: ${boardW.roundToInt()}×${boardH.roundToInt()}px (单格 ${(cellW).roundToInt()}×${(cellH).roundToInt()}px)",
            w * 0.5f,
            topPanelTop + 78f * density,
            coordTextPaint
        )

        // 6. Draw Bottom Control Panel (Fine-tune nudge bar + Save/Cancel actions)
        val bottomPanelH = 104f * density
        val bottomPanelTop = (h - bottomPanelH - 22f * density).coerceAtLeast(topPanelRect.bottom + 12f * density)
        val bottomPanelRect = RectF(10f * density, bottomPanelTop, w - 10f * density, bottomPanelTop + bottomPanelH)
        canvas.drawRoundRect(bottomPanelRect, 12f * density, 12f * density, hudPanelPaint)
        canvas.drawRoundRect(bottomPanelRect, 12f * density, 12f * density, hudStrokePaint)

        // Row 1 of bottom panel: 7 micro-adjustment nudge buttons including "铺满宽"
        val nudgeY = bottomPanelTop + 10f * density
        val nudgeH = 34f * density
        val nudgeW = (totalChipW - gap * 6f) / 7f
        val nudgeLabels = listOf("◀左", "▲上", "▼下", "▶右", "－缩", "＋放", "铺满宽")
        val nudgeRects = listOf(
            nudgeLeftRect,
            nudgeUpRect,
            nudgeDownRect,
            nudgeRightRect,
            nudgeShrinkRect,
            nudgeExpandRect,
            fullWidthSnapRect
        )
        for (idx in nudgeRects.indices) {
            val nl = 16f * density + idx * (nudgeW + gap)
            nudgeRects[idx].set(nl, nudgeY, nl + nudgeW, nudgeY + nudgeH)
            val paint = if (idx == 6) chipAccentPaint else chipInactivePaint
            drawChipButton(canvas, nudgeRects[idx], nudgeLabels[idx], paint)
        }

        // Row 2 of bottom panel: Cancel | Reset Auto | Save Calibration | Save & Analyze
        val actY = nudgeY + nudgeH + 10f * density
        val actH = 40f * density
        val actW = (totalChipW - gap * 3f) / 4f

        cancelButtonRect.set(16f * density, actY, 16f * density + actW, actY + actH)
        resetAutoButtonRect.set(cancelButtonRect.right + gap, actY, cancelButtonRect.right + gap + actW, actY + actH)
        confirmButtonRect.set(resetAutoButtonRect.right + gap, actY, resetAutoButtonRect.right + gap + actW, actY + actH)
        confirmAndAnalyzeButtonRect.set(confirmButtonRect.right + gap, actY, confirmButtonRect.right + gap + actW, actY + actH)

        drawChipButton(canvas, cancelButtonRect, "取消", btnCancelPaint)
        drawChipButton(canvas, resetAutoButtonRect, "恢复自动", btnResetPaint)
        drawChipButton(canvas, confirmButtonRect, "保存校准", btnConfirmPaint)
        drawChipButton(canvas, confirmAndAnalyzeButtonRect, "保存并分析", btnConfirmAnalyzePaint)
    }

    private fun drawCornerHandle(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawCircle(cx, cy, radius, handleFillPaint)
        canvas.drawCircle(cx, cy, radius, handleRingPaint)
    }

    private fun drawChipButton(canvas: Canvas, rect: RectF, text: String, bgPaint: Paint) {
        canvas.drawRoundRect(rect, 8f * density, 8f * density, bgPaint)
        canvas.drawRoundRect(rect, 8f * density, 8f * density, hudStrokePaint)
        val ty = rect.centerY() - (smallBtnTextPaint.descent() + smallBtnTextPaint.ascent()) * 0.5f
        canvas.drawText(text, rect.centerX(), ty, smallBtnTextPaint)
    }

    private fun nudgeBoard(dx: Float, dy: Float, dScalePx: Float = 0f) {
        val viewW = (if (width > 0) width else screenWidth).toFloat()
        val viewH = (if (height > 0) height else screenHeight).toFloat()
        val minSize = 120f * density

        if (dScalePx != 0f) {
            val nl = (leftPx - dScalePx).coerceAtLeast(0f)
            val nt = (topPx - dScalePx).coerceAtLeast(0f)
            val nr = (rightPx + dScalePx).coerceAtMost(viewW)
            val nb = (bottomPx + dScalePx).coerceAtMost(viewH)
            if (nr - nl >= minSize && nb - nt >= minSize) {
                leftPx = nl
                topPx = nt
                rightPx = nr
                bottomPx = nb
            }
        } else {
            val bw = rightPx - leftPx
            val bh = bottomPx - topPx
            leftPx = (leftPx + dx).coerceIn(0f, (viewW - bw).coerceAtLeast(0f))
            topPx = (topPx + dy).coerceIn(0f, (viewH - bh).coerceAtLeast(0f))
            rightPx = leftPx + bw
            bottomPx = topPx + bh
        }
        invalidate()
    }

    private fun snapToSquareAspect() {
        val viewW = (if (width > 0) width else screenWidth).toFloat()
        val viewH = (if (height > 0) height else screenHeight).toFloat()
        val cx = (leftPx + rightPx) * 0.5f
        val cy = (topPx + bottomPx) * 0.5f
        val side = ((rightPx - leftPx + bottomPx - topPx) * 0.5f)
            .coerceIn(120f * density, min(viewW, viewH))
        leftPx = (cx - side * 0.5f).coerceIn(0f, viewW - side)
        topPx = (cy - side * 0.5f).coerceIn(0f, viewH - side)
        rightPx = leftPx + side
        bottomPx = topPx + side
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val viewW = (if (width > 0) width else screenWidth).toFloat()
        val viewH = (if (height > 0) height else screenHeight).toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 1. Check bottom action buttons
                if (confirmButtonRect.contains(x, y)) {
                    performSaveCalibration(analyzeImmediately = false)
                    return true
                }
                if (confirmAndAnalyzeButtonRect.contains(x, y)) {
                    performSaveCalibration(analyzeImmediately = true)
                    return true
                }
                if (resetAutoButtonRect.contains(x, y)) {
                    onResetToAuto()
                    return true
                }
                if (cancelButtonRect.contains(x, y)) {
                    onCancel()
                    return true
                }

                // 2. Check top HUD chips
                if (autoSnapChipRect.contains(x, y)) {
                    val autoDetector = onRequestAutoDetectBoard
                    if (autoDetector != null) {
                        statusHintText = "正在从游戏画面自动识别 8×8 棋盘外框..."
                        invalidate()
                        autoDetector { detectedRect ->
                            if (detectedRect != null && detectedRect.width() >= 80f && detectedRect.height() >= 80f) {
                                setBoardPixelRect(
                                    detectedRect.left,
                                    detectedRect.top,
                                    detectedRect.right,
                                    detectedRect.bottom
                                )
                                statusHintText = "✓ 已自动贴合棋盘！确认网格对齐后点击「保存并分析」"
                            } else {
                                statusHintText = "未自动识别到边界，请点击「铺满宽」或手动拖动四角对齐"
                                invalidate()
                            }
                        }
                    } else {
                        statusHintText = "请先启动屏幕捕捉后再使用 AI 自动贴合，或直接手动拖动四角"
                        invalidate()
                    }
                    return true
                }
                if (squareLockChipRect.contains(x, y)) {
                    lockSquareAspect = !lockSquareAspect
                    if (lockSquareAspect) {
                        snapToSquareAspect()
                        statusHintText = "已开启 1:1 正方形锁定"
                    } else {
                        statusHintText = "已切换为自由长宽比调整"
                        invalidate()
                    }
                    return true
                }
                if (orientationChipRect.contains(x, y)) {
                    currentOrientationMode = when (currentOrientationMode) {
                        BoardOrientationMode.AUTO -> BoardOrientationMode.WHITE_BOTTOM
                        BoardOrientationMode.WHITE_BOTTOM -> BoardOrientationMode.BLACK_BOTTOM
                        BoardOrientationMode.BLACK_BOTTOM -> BoardOrientationMode.AUTO
                    }
                    onOrientationModeChanged?.invoke(currentOrientationMode)
                    statusHintText = "已切换棋盘方向: ${currentOrientationMode.displayName}"
                    invalidate()
                    return true
                }
                if (userColorChipRect.contains(x, y)) {
                    currentUserColor = currentUserColor.opposite()
                    onUserColorChanged?.invoke(currentUserColor)
                    statusHintText = "已切换我执颜色: ${currentUserColor.displayName}"
                    invalidate()
                    return true
                }

                // 3. Check fine-tune nudge buttons
                val stepPx = max(2f, 1.5f * density)
                if (nudgeLeftRect.contains(x, y)) {
                    nudgeBoard(-stepPx, 0f)
                    return true
                }
                if (nudgeRightRect.contains(x, y)) {
                    nudgeBoard(stepPx, 0f)
                    return true
                }
                if (nudgeUpRect.contains(x, y)) {
                    nudgeBoard(0f, -stepPx)
                    return true
                }
                if (nudgeDownRect.contains(x, y)) {
                    nudgeBoard(0f, stepPx)
                    return true
                }
                if (nudgeShrinkRect.contains(x, y)) {
                    nudgeBoard(0f, 0f, -stepPx)
                    return true
                }
                if (nudgeExpandRect.contains(x, y)) {
                    nudgeBoard(0f, 0f, stepPx)
                    return true
                }
                if (fullWidthSnapRect.contains(x, y)) {
                    snapToFullScreenWidthSquare()
                    return true
                }

                // 4. Check 4 corner handles & interior drag
                val hitR = 38f * density
                activeHandle = when {
                    hypot((x - leftPx).toDouble(), (y - topPx).toDouble()) <= hitR -> HANDLE_TOP_LEFT
                    hypot((x - rightPx).toDouble(), (y - topPx).toDouble()) <= hitR -> HANDLE_TOP_RIGHT
                    hypot((x - leftPx).toDouble(), (y - bottomPx).toDouble()) <= hitR -> HANDLE_BOTTOM_LEFT
                    hypot((x - rightPx).toDouble(), (y - bottomPx).toDouble()) <= hitR -> HANDLE_BOTTOM_RIGHT
                    x in leftPx..rightPx && y in topPx..bottomPx -> HANDLE_MOVE_WHOLE
                    else -> HANDLE_NONE
                }
                lastTouchX = x
                lastTouchY = y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastTouchX
                val dy = y - lastTouchY
                lastTouchX = x
                lastTouchY = y
                val minSize = 120f * density

                when (activeHandle) {
                    HANDLE_TOP_LEFT -> {
                        if (lockSquareAspect) {
                            val delta = (dx + dy) * 0.5f
                            val maxDelta = min(rightPx - leftPx - minSize, bottomPx - topPx - minSize)
                            val clamped = delta.coerceIn(-min(leftPx, topPx), maxDelta)
                            leftPx += clamped
                            topPx += clamped
                        } else {
                            leftPx = (leftPx + dx).coerceIn(0f, rightPx - minSize)
                            topPx = (topPx + dy).coerceIn(0f, bottomPx - minSize)
                        }
                        invalidate()
                    }
                    HANDLE_TOP_RIGHT -> {
                        if (lockSquareAspect) {
                            val delta = (dx - dy) * 0.5f
                            val minDelta = -(min(rightPx - leftPx - minSize, bottomPx - topPx - minSize))
                            val maxDelta = min(viewW - rightPx, topPx)
                            val clamped = delta.coerceIn(minDelta, maxDelta)
                            rightPx += clamped
                            topPx -= clamped
                        } else {
                            rightPx = (rightPx + dx).coerceIn(leftPx + minSize, viewW)
                            topPx = (topPx + dy).coerceIn(0f, bottomPx - minSize)
                        }
                        invalidate()
                    }
                    HANDLE_BOTTOM_LEFT -> {
                        if (lockSquareAspect) {
                            val delta = (-dx + dy) * 0.5f
                            val minDelta = -(min(rightPx - leftPx - minSize, bottomPx - topPx - minSize))
                            val maxDelta = min(leftPx, viewH - bottomPx)
                            val clamped = delta.coerceIn(minDelta, maxDelta)
                            leftPx -= clamped
                            bottomPx += clamped
                        } else {
                            leftPx = (leftPx + dx).coerceIn(0f, rightPx - minSize)
                            bottomPx = (bottomPx + dy).coerceIn(topPx + minSize, viewH)
                        }
                        invalidate()
                    }
                    HANDLE_BOTTOM_RIGHT -> {
                        if (lockSquareAspect) {
                            val delta = (dx + dy) * 0.5f
                            val minDelta = -(min(rightPx - leftPx - minSize, bottomPx - topPx - minSize))
                            val maxDelta = min(viewW - rightPx, viewH - bottomPx)
                            val clamped = delta.coerceIn(minDelta, maxDelta)
                            rightPx += clamped
                            bottomPx += clamped
                        } else {
                            rightPx = (rightPx + dx).coerceIn(leftPx + minSize, viewW)
                            bottomPx = (bottomPx + dy).coerceIn(topPx + minSize, viewH)
                        }
                        invalidate()
                    }
                    HANDLE_MOVE_WHOLE -> {
                        val bw = rightPx - leftPx
                        val bh = bottomPx - topPx
                        leftPx = (leftPx + dx).coerceIn(0f, (viewW - bw).coerceAtLeast(0f))
                        topPx = (topPx + dy).coerceIn(0f, (viewH - bh).coerceAtLeast(0f))
                        rightPx = leftPx + bw
                        bottomPx = topPx + bh
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeHandle = HANDLE_NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    companion object {
        private const val HANDLE_NONE = 0
        private const val HANDLE_TOP_LEFT = 1
        private const val HANDLE_TOP_RIGHT = 2
        private const val HANDLE_BOTTOM_LEFT = 3
        private const val HANDLE_BOTTOM_RIGHT = 4
        private const val HANDLE_MOVE_WHOLE = 5
    }
}
