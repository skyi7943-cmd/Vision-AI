package com.example.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.model.OverlayButtonSymbol
import com.example.model.PerformanceMode
import com.example.overlay.FloatingOverlayController
import com.example.vision.CoordinateTransformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Foreground service managing MediaProjection screen capture, VirtualDisplay, ImageReader,
 * and the floating ♟ + ⊞ 校准 capsule + move arrow + in-game board calibration overlay windows.
 */
class ScreenCaptureOverlayService : Service(), TrainerSessionOrchestrator.ScreenCaptureServiceBridge {

    private lateinit var orchestrator: TrainerSessionOrchestrator
    private lateinit var overlayController: FloatingOverlayController
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var currentCaptureWidth: Int = 0
    private var currentCaptureHeight: Int = 0
    private var currentScreenWidth: Int = 0
    private var currentScreenHeight: Int = 0
    private var currentRotation: Int = Surface.ROTATION_0

    private var activeAnalysisJob: Job? = null

    private data class PendingFrameRequest(
        val requestedAtNanoTime: Long,
        val deferred: CompletableDeferred<Bitmap>
    )

    @Volatile
    private var pendingFrameRequest: PendingFrameRequest? = null

    private val frameLock = Any()
    private var latestCleanArgbBuffer: IntArray? = null
    private var latestCleanWidth: Int = 0
    private var latestCleanHeight: Int = 0
    private var latestCleanTimestampNs: Long = 0L

    // Snapshot of the clean game screen taken right before the calibration overlay opens
    private var preCalibrationArgbSnapshot: IntArray? = null
    private var preCalibrationWidth: Int = 0
    private var preCalibrationHeight: Int = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            serviceScope.launch {
                handleSystemProjectionStopped("系统或用户终止了屏幕捕捉授权")
            }
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // Screen locked: cancel analysis and clear any overlay arrow immediately
                    activeAnalysisJob?.cancel()
                    overlayController.closeManualCalibrationOverlay()
                    overlayController.clearArrowImmediately()
                    overlayController.updateButtonState(OverlayButtonSymbol.READY, null)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        orchestrator = TrainerSessionOrchestrator.getInstance(applicationContext)
        orchestrator.serviceBridge = this

        val thread = HandlerThread("ChessVisionImageReaderThread").apply { start() }
        imageThread = thread
        imageHandler = Handler(thread.looper)

        overlayController = FloatingOverlayController(
            context = applicationContext,
            onAnalyzeClicked = { requestSingleAnalysisFromOverlay() },
            onClearArrowLongPressed = {
                activeAnalysisJob?.cancel()
                orchestrator.stockfishController.cancelCurrentAnalysis()
                overlayController.clearArrowImmediately()
                overlayController.updateButtonState(OverlayButtonSymbol.READY, null)
                orchestrator.updateRuntimeStatus {
                    it.copy(overlayButtonSymbol = OverlayButtonSymbol.READY)
                }
            },
            onCalibrateClicked = {
                openManualCalibrationOverlay()
            },
            onManualCalibrationSaved = { rect, analyzeImmediately ->
                orchestrator.setManualCalibration(rect)
                overlayController.updateCalibrationIndicator(rect != null && orchestrator.preferManualCalibration.value)
                if (analyzeImmediately && rect != null) {
                    serviceScope.launch {
                        // Wait briefly for the calibration overlay window removal to commit to SurfaceFlinger
                        delay(120L)
                        requestSingleAnalysisFromOverlay()
                    }
                }
            },
            onAutoDetectBoardRequested = { callback ->
                autoDetectBoardInCurrentGameFrame(callback)
            },
            onOrientationModeChanged = { mode ->
                orchestrator.setBoardOrientationMode(mode)
            },
            onUserColorChanged = { color ->
                orchestrator.setUserColor(color)
            }
        )

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenStateReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_ALL -> {
                stopServiceAndRelease()
                return START_NOT_STICKY
            }
            ACTION_START_CAPTURE -> {
                startForegroundWithNotification()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    orchestrator.updateRuntimeStatus {
                        it.copy(
                            captureActive = false,
                            captureStatusText = "授权无效或已取消，请重新点击一键启动"
                        )
                    }
                    stopSelf()
                    return START_NOT_STICKY
                }

                startCapturePipeline(resultCode, resultData)
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        createNotificationChannel()
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCapturePipeline(resultCode: Int, resultData: Intent) {
        releaseVirtualDisplayAndReader()
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null

        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            mpManager.getMediaProjection(resultCode, resultData)
        } catch (_: Exception) {
            null
        }

        if (projection == null) {
            orchestrator.updateRuntimeStatus {
                it.copy(
                    captureActive = false,
                    captureStatusText = "获取 MediaProjection 失败: 授权结果已失效"
                )
            }
            stopSelf()
            return
        }

        // Register callback BEFORE createVirtualDisplay (required on Android 14+)
        projection.registerCallback(projectionCallback, Handler(mainLooper))
        mediaProjection = projection

        setupVirtualDisplayForCurrentDisplay()

        if (overlayController.canDrawOverlays()) {
            val hasCal = orchestrator.manualCalibration.value != null && orchestrator.preferManualCalibration.value
            overlayController.showOverlays(hasManualCalibration = hasCal)
            overlayController.updateButtonState(OverlayButtonSymbol.READY, if (hasCal) "已校准" else null)
            orchestrator.updateRuntimeStatus {
                it.copy(
                    overlayActive = true,
                    overlayPermissionGranted = true,
                    overlayStatusText = "运行中 (单击 ♟ 分析，点击 ⊞ 校准 在游戏界面对齐棋盘，长按清除箭头)",
                    overlayButtonSymbol = OverlayButtonSymbol.READY
                )
            }
        } else {
            orchestrator.updateRuntimeStatus {
                it.copy(
                    overlayActive = false,
                    overlayPermissionGranted = false,
                    overlayStatusText = "未授予悬浮窗权限"
                )
            }
        }

        orchestrator.warmUpModelAndEngine()
    }

    private fun setupVirtualDisplayForCurrentDisplay() {
        val projection = mediaProjection ?: return
        releaseVirtualDisplayAndReader()

        val (screenW, screenH, densityDpi, rotation) = queryRealScreenMetrics()
        currentScreenWidth = screenW
        currentScreenHeight = screenH
        currentRotation = rotation

        val perfMode = orchestrator.performanceMode.value
        val effectiveConfig = perfMode.resolveForDevice(
            availableProcessors = Runtime.getRuntime().availableProcessors(),
            sourceScreenWidth = screenW,
            sourceScreenHeight = screenH
        )

        currentCaptureWidth = effectiveConfig.captureWidth
        currentCaptureHeight = effectiveConfig.captureHeight

        val reader = ImageReader.newInstance(
            currentCaptureWidth,
            currentCaptureHeight,
            PixelFormat.RGBA_8888,
            2
        )

        reader.setOnImageAvailableListener({ ir ->
            val img = try {
                ir.acquireLatestImage()
            } catch (_: Exception) {
                null
            } ?: return@setOnImageAvailableListener

            try {
                val frameTs = img.timestamp
                val req = pendingFrameRequest
                val planes = img.planes
                if (planes != null && planes.isNotEmpty()) {
                    val plane = planes[0]
                    val argb = ImageFrameConverter.rgbaBufferToArgbIntArray(
                        buffer = plane.buffer,
                        width = img.width,
                        height = img.height,
                        pixelStride = plane.pixelStride,
                        rowStride = plane.rowStride
                    )
                    // Only overwrite latestCleanArgbBuffer when the full-screen calibration overlay is NOT covering the screen
                    if (!overlayController.isCalibrationOverlayShowing) {
                        synchronized(frameLock) {
                            latestCleanArgbBuffer = argb
                            latestCleanWidth = img.width
                            latestCleanHeight = img.height
                            latestCleanTimestampNs = frameTs
                        }
                    }

                    // If an analysis click is waiting for a post-clear frame, complete it immediately
                    if (req != null && !req.deferred.isCompleted && frameTs >= req.requestedAtNanoTime) {
                        val bmp = Bitmap.createBitmap(argb, img.width, img.height, Bitmap.Config.ARGB_8888)
                        if (req.deferred.complete(bmp)) {
                            pendingFrameRequest = null
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    img.close()
                } catch (_: Exception) {
                }
            }
        }, imageHandler)

        val vDisplay = projection.createVirtualDisplay(
            "ChessVisionTrainerCapture",
            currentCaptureWidth,
            currentCaptureHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            imageHandler
        )

        imageReader = reader
        virtualDisplay = vDisplay

        orchestrator.updateRuntimeStatus {
            it.copy(
                captureActive = true,
                captureStatusText = "捕捉中 (${currentCaptureWidth}×${currentCaptureHeight}, 物理屏 ${screenW}×${screenH})"
            )
        }
    }

    override fun requestSingleAnalysisFromOverlay() {
        // Cancel any previous in-flight analysis and bump request ID
        activeAnalysisJob?.cancel()
        val requestId = orchestrator.stockfishController.nextRequestId()

        orchestrator.updateRuntimeStatus {
            it.copy(overlayButtonSymbol = OverlayButtonSymbol.ANALYZING)
        }

        // 1. Clear old arrow first and wait for UI commit before capturing the frame
        overlayController.clearArrowAndNotifyCommitted { clearedAtNanoTime ->
            activeAnalysisJob = serviceScope.launch {
                var capturedBitmap: Bitmap? = null
                try {
                    capturedBitmap = acquirePostClearFrameWithTimeout(clearedAtNanoTime)
                    if (capturedBitmap == null) {
                        overlayController.clearArrowImmediately()
                        overlayController.updateButtonState(OverlayButtonSymbol.FAILED, "无画面")
                        orchestrator.updateRuntimeStatus {
                            it.copy(overlayButtonSymbol = OverlayButtonSymbol.FAILED)
                        }
                        return@launch
                    }

                    val (screenW, screenH, _, rotation) = queryRealScreenMetrics()
                    if (screenW != currentScreenWidth || screenH != currentScreenHeight || rotation != currentRotation) {
                        // Screen geometry changed mid-capture: rebuild VirtualDisplay and abort stale frame
                        setupVirtualDisplayForCurrentDisplay()
                        overlayController.clearArrowImmediately()
                        overlayController.updateButtonState(OverlayButtonSymbol.FAILED, "屏幕变更")
                        orchestrator.updateRuntimeStatus {
                            it.copy(overlayButtonSymbol = OverlayButtonSymbol.FAILED)
                        }
                        return@launch
                    }

                    val (winOffX, winOffY) = overlayController.getArrowWindowScreenOffset()
                    val captureToScreen = CoordinateTransformer.CaptureToScreenTransform(
                        captureWidth = capturedBitmap.width,
                        captureHeight = capturedBitmap.height,
                        screenWidthPx = screenW,
                        screenHeightPx = screenH,
                        overlayWindowOffsetX = winOffX,
                        overlayWindowOffsetY = winOffY,
                        screenRotation = rotation
                    )

                    val effectiveConfig = orchestrator.performanceMode.value.resolveForDevice(
                        availableProcessors = Runtime.getRuntime().availableProcessors(),
                        sourceScreenWidth = screenW,
                        sourceScreenHeight = screenH
                    )

                    val bmpForAnalysis = capturedBitmap
                    val report = withContext(Dispatchers.Default) {
                        orchestrator.executeSingleFrameAnalysis(
                            requestId = requestId,
                            capturedBitmap = bmpForAnalysis,
                            captureToScreenTransform = captureToScreen,
                            effectiveConfig = effectiveConfig,
                            onShowArrow = { coords -> overlayController.showArrow(coords) },
                            onClearArrow = { overlayController.clearArrowImmediately() },
                            onUpdateButton = { sym, badge ->
                                overlayController.updateButtonState(sym, badge)
                                orchestrator.updateRuntimeStatus { st ->
                                    st.copy(overlayButtonSymbol = sym)
                                }
                            }
                        )
                    }

                    if (!report.success && report.arrowOverlay == null) {
                        overlayController.clearArrowImmediately()
                    }
                } catch (_: CancellationException) {
                    // Superseded by newer request or stopped
                } catch (_: Exception) {
                    overlayController.clearArrowImmediately()
                    overlayController.updateButtonState(OverlayButtonSymbol.FAILED, "异常")
                    orchestrator.updateRuntimeStatus {
                        it.copy(overlayButtonSymbol = OverlayButtonSymbol.FAILED)
                    }
                } finally {
                    capturedBitmap?.recycle()
                }
            }
        }
    }

    /**
     * Waits for a fresh post-overlay frame from [ImageReader] using [CompletableDeferred]
     * and [withTimeout] (no busy waiting or Thread.sleep). If the display is completely static
     * and SurfaceFlinger does not emit a new buffer within 400ms after the overlay clear pulse,
     * uses the clean in-memory ARGB buffer.
     */
    private suspend fun acquirePostClearFrameWithTimeout(clearedAtNanoTime: Long): Bitmap? {
        val deferred = CompletableDeferred<Bitmap>()
        val req = PendingFrameRequest(clearedAtNanoTime, deferred)
        pendingFrameRequest = req

        return try {
            withTimeout(420L) {
                deferred.await()
            }
        } catch (_: Exception) {
            pendingFrameRequest = null
            // Fallback for static screen where VirtualDisplay did not emit a new frame buffer
            delay(40L)
            synchronized(frameLock) {
                val buf = latestCleanArgbBuffer
                val w = latestCleanWidth
                val h = latestCleanHeight
                if (buf != null && w > 0 && h > 0) {
                    Bitmap.createBitmap(buf.clone(), w, h, Bitmap.Config.ARGB_8888)
                } else {
                    null
                }
            }
        } finally {
            if (pendingFrameRequest === req) {
                pendingFrameRequest = null
            }
        }
    }

    override fun clearOverlayArrow() {
        overlayController.clearArrowImmediately()
        overlayController.updateButtonState(OverlayButtonSymbol.READY, null)
        orchestrator.updateRuntimeStatus {
            it.copy(overlayButtonSymbol = OverlayButtonSymbol.READY)
        }
    }

    override fun updateOverlayCalibrationIndicator(active: Boolean) {
        overlayController.updateCalibrationIndicator(active)
    }

    override fun openManualCalibrationOverlay() {
        activeAnalysisJob?.cancel()
        val (screenW, screenH, _, rotation) = queryRealScreenMetrics()

        // Snapshot the clean game screen buffer before the calibration overlay appears on screen
        var autoDetectedScreenRect: RectF? = null
        synchronized(frameLock) {
            val buf = latestCleanArgbBuffer
            val capW = latestCleanWidth
            val capH = latestCleanHeight
            if (buf != null && capW > 0 && capH > 0) {
                val snap = buf.clone()
                preCalibrationArgbSnapshot = snap
                preCalibrationWidth = capW
                preCalibrationHeight = capH

                // If user has no saved calibration yet, automatically detect the 8x8 checkerboard
                // on the current game screen so the calibration box snaps right onto the game board!
                if (orchestrator.manualCalibration.value == null) {
                    val detectedCapRect = CoordinateTransformer.detectCheckerboardRectFromPixels(snap, capW, capH)
                    if (detectedCapRect != null && detectedCapRect.isValid(64f)) {
                        val (winOffX, winOffY) = overlayController.getArrowWindowScreenOffset()
                        val capToScreen = CoordinateTransformer.CaptureToScreenTransform(
                            captureWidth = capW,
                            captureHeight = capH,
                            screenWidthPx = screenW,
                            screenHeightPx = screenH,
                            overlayWindowOffsetX = winOffX,
                            overlayWindowOffsetY = winOffY,
                            screenRotation = rotation
                        )
                        val ovRect = capToScreen.captureRectToOverlayRect(detectedCapRect)
                        autoDetectedScreenRect = RectF(ovRect.left, ovRect.top, ovRect.right, ovRect.bottom)
                    }
                }
            }
        }

        overlayController.showManualCalibrationOverlay(
            initialCalibration = orchestrator.manualCalibration.value,
            screenWidth = screenW,
            screenHeight = screenH,
            screenRotation = rotation,
            initialOrientationMode = orchestrator.boardOrientationMode.value,
            initialUserColor = orchestrator.userColor.value,
            autoDetectedInitialRectPx = autoDetectedScreenRect
        )
    }

    private fun autoDetectBoardInCurrentGameFrame(onResult: (RectF?) -> Unit) {
        serviceScope.launch {
            val (screenW, screenH, _, rotation) = queryRealScreenMetrics()
            val (snapBuf, capW, capH) = synchronized(frameLock) {
                val b = preCalibrationArgbSnapshot ?: latestCleanArgbBuffer
                val w = if (preCalibrationArgbSnapshot != null) preCalibrationWidth else latestCleanWidth
                val h = if (preCalibrationArgbSnapshot != null) preCalibrationHeight else latestCleanHeight
                Triple(b?.clone(), w, h)
            }

            if (snapBuf == null || capW <= 0 || capH <= 0) {
                onResult(null)
                return@launch
            }

            val detectedOverlayRect = withContext(Dispatchers.Default) {
                var bmp: Bitmap? = null
                try {
                    bmp = Bitmap.createBitmap(snapBuf, capW, capH, Bitmap.Config.ARGB_8888)
                    val perf = orchestrator.performanceMode.value.resolveForDevice(
                        availableProcessors = Runtime.getRuntime().availableProcessors(),
                        sourceScreenWidth = screenW,
                        sourceScreenHeight = screenH
                    )
                    val capRect = orchestrator.yoloDetector.detectBoardRectForCalibration(
                        captureBitmap = bmp,
                        cpuThreads = perf.visionCpuThreads
                    ) ?: return@withContext null

                    val (winOffX, winOffY) = overlayController.getArrowWindowScreenOffset()
                    val capToScreen = CoordinateTransformer.CaptureToScreenTransform(
                        captureWidth = capW,
                        captureHeight = capH,
                        screenWidthPx = screenW,
                        screenHeightPx = screenH,
                        overlayWindowOffsetX = winOffX,
                        overlayWindowOffsetY = winOffY,
                        screenRotation = rotation
                    )
                    val ov = capToScreen.captureRectToOverlayRect(capRect)
                    RectF(ov.left, ov.top, ov.right, ov.bottom)
                } catch (_: Exception) {
                    null
                } finally {
                    bmp?.recycle()
                }
            }
            onResult(detectedOverlayRect)
        }
    }

    override fun onPerformanceModeUpdated(mode: PerformanceMode) {
        if (mediaProjection != null) {
            overlayController.clearArrowImmediately()
            setupVirtualDisplayForCurrentDisplay()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation or resolution changed: immediately clear any stale arrow and rebuild capture mapping
        activeAnalysisJob?.cancel()
        overlayController.closeManualCalibrationOverlay()
        overlayController.clearArrowImmediately()
        overlayController.updateButtonState(OverlayButtonSymbol.READY, null)
        if (mediaProjection != null) {
            setupVirtualDisplayForCurrentDisplay()
        }
    }

    private fun handleSystemProjectionStopped(reason: String) {
        activeAnalysisJob?.cancel()
        overlayController.clearArrowImmediately()
        overlayController.hideAndReleaseAll()
        releaseVirtualDisplayAndReader()
        mediaProjection = null
        orchestrator.updateRuntimeStatus {
            it.copy(
                captureActive = false,
                captureStatusText = reason,
                overlayActive = false,
                overlayStatusText = "已随捕捉停止而关闭",
                overlayButtonSymbol = OverlayButtonSymbol.READY
            )
        }
        stopSelf()
    }

    override fun stopServiceAndRelease() {
        activeAnalysisJob?.cancel()
        activeAnalysisJob = null
        pendingFrameRequest?.deferred?.cancel()
        pendingFrameRequest = null

        overlayController.clearArrowImmediately()
        overlayController.hideAndReleaseAll()
        releaseVirtualDisplayAndReader()

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun releaseVirtualDisplayAndReader() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null

        synchronized(frameLock) {
            latestCleanArgbBuffer = null
            latestCleanWidth = 0
            latestCleanHeight = 0
            preCalibrationArgbSnapshot = null
            preCalibrationWidth = 0
            preCalibrationHeight = 0
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: Exception) {
        }
        stopServiceAndRelease()
        if (orchestrator.serviceBridge === this) {
            orchestrator.serviceBridge = null
        }
        try {
            imageThread?.quitSafely()
        } catch (_: Exception) {
        }
        imageThread = null
        imageHandler = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private data class ScreenMetricsQuad(
        val widthPx: Int,
        val heightPx: Int,
        val densityDpi: Int,
        val rotation: Int
    )

    private fun queryRealScreenMetrics(): ScreenMetricsQuad {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = wm.defaultDisplay
        @Suppress("DEPRECATION")
        display.getRealMetrics(dm)
        val rotation = display.rotation
        val width = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.maximumWindowMetrics.bounds.width()
        } else {
            dm.widthPixels
        }
        val height = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.maximumWindowMetrics.bounds.height()
        } else {
            dm.heightPixels
        }
        return ScreenMetricsQuad(
            widthPx = width.coerceAtLeast(320),
            heightPx = height.coerceAtLeast(480),
            densityDpi = dm.densityDpi.coerceAtLeast(160),
            rotation = rotation
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用于保持屏幕棋盘捕捉与悬浮窗分析/校准按钮运行"
                setShowBadge(false)
            }
            mgr.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ScreenCaptureOverlayService::class.java).apply {
            action = ACTION_STOP_ALL
        }
        val pendingStop = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_content))
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.btn_stop_all),
                pendingStop
            )
            .build()
    }

    companion object {
        const val CHANNEL_ID = "chess_vision_capture_channel"
        const val NOTIFICATION_ID = 4101
        const val ACTION_START_CAPTURE = "com.example.action.START_CAPTURE"
        const val ACTION_STOP_ALL = "com.example.action.STOP_ALL"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
    }
}
