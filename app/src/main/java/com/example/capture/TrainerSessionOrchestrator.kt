package com.example.capture

import android.content.Context
import android.graphics.Bitmap
import com.example.chess.ChessBoardState
import com.example.chess.GameStateTracker
import com.example.engine.EngineAnalysisOutcome
import com.example.engine.StockfishEngineController
import com.example.model.AnalysisReport
import com.example.model.BoardOrientation
import com.example.model.BoardOrientationMode
import com.example.model.CastlingRights
import com.example.model.ComponentRuntimeStatus
import com.example.model.ManualCalibrationNormalizedRect
import com.example.model.MoveSpecialType
import com.example.model.OverlayButtonSymbol
import com.example.model.PerformanceMode
import com.example.model.PieceColor
import com.example.vision.CoordinateTransformer
import com.example.vision.YoloChessDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Single-process session coordinator connecting:
 * Screen Capture -> YOLOv8m Vision Detector -> Coordinate Transformer ->
 * FEN Position Reconstruction & Legality Validator -> Stockfish 18 UCI Engine -> Floating Overlay.
 */
class TrainerSessionOrchestrator(
    context: Context,
    val yoloDetector: YoloChessDetector = YoloChessDetector(context.applicationContext),
    val stockfishController: StockfishEngineController = StockfishEngineController(context.applicationContext)
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val gameStateTracker = GameStateTracker()

    private val _runtimeStatus = MutableStateFlow(ComponentRuntimeStatus())
    val runtimeStatus: StateFlow<ComponentRuntimeStatus> = _runtimeStatus.asStateFlow()

    private val _performanceMode = MutableStateFlow(PerformanceMode.MEDIUM)
    val performanceMode: StateFlow<PerformanceMode> = _performanceMode.asStateFlow()

    private val _userColor = MutableStateFlow(PieceColor.WHITE)
    val userColor: StateFlow<PieceColor> = _userColor.asStateFlow()

    private val _boardOrientationMode = MutableStateFlow(BoardOrientationMode.AUTO)
    val boardOrientationMode: StateFlow<BoardOrientationMode> = _boardOrientationMode.asStateFlow()

    private val _manualCalibration = MutableStateFlow<ManualCalibrationNormalizedRect?>(null)
    val manualCalibration: StateFlow<ManualCalibrationNormalizedRect?> = _manualCalibration.asStateFlow()

    private val _preferManualCalibration = MutableStateFlow(false)
    val preferManualCalibration: StateFlow<Boolean> = _preferManualCalibration.asStateFlow()

    private val _configuredCastling = MutableStateFlow(CastlingRights.ALL)
    val configuredCastling: StateFlow<CastlingRights> = _configuredCastling.asStateFlow()

    private val _configuredEnPassant = MutableStateFlow("")
    val configuredEnPassant: StateFlow<String> = _configuredEnPassant.asStateFlow()

    private val _activeColorOverride = MutableStateFlow<PieceColor?>(null)
    val activeColorOverride: StateFlow<PieceColor?> = _activeColorOverride.asStateFlow()

    private val _midgameConfirmed = MutableStateFlow(false)
    val midgameConfirmed: StateFlow<Boolean> = _midgameConfirmed.asStateFlow()

    private val _latestAnalysisReport = MutableStateFlow<AnalysisReport?>(null)
    val latestAnalysisReport: StateFlow<AnalysisReport?> = _latestAnalysisReport.asStateFlow()

    @Volatile
    private var currentConfirmedFen: String? = null

    @Volatile
    private var activeAnalysisJob: Job? = null

    @Volatile
    var serviceBridge: ScreenCaptureServiceBridge? = null

    interface ScreenCaptureServiceBridge {
        fun requestSingleAnalysisFromOverlay()
        fun clearOverlayArrow()
        fun openManualCalibrationOverlay()
        fun updateOverlayCalibrationIndicator(active: Boolean) {}
        fun onPerformanceModeUpdated(mode: PerformanceMode)
        fun stopServiceAndRelease()
    }

    fun setPerformanceMode(mode: PerformanceMode) {
        _performanceMode.value = mode
        serviceBridge?.onPerformanceModeUpdated(mode)
    }

    fun setUserColor(color: PieceColor) {
        _userColor.value = color
    }

    fun setBoardOrientationMode(mode: BoardOrientationMode) {
        _boardOrientationMode.value = mode
        // Orientation mode changed: clear any existing arrow whose coordinates relied on old orientation
        serviceBridge?.clearOverlayArrow()
    }

    fun setManualCalibration(rect: ManualCalibrationNormalizedRect?) {
        _manualCalibration.value = rect
        _preferManualCalibration.value = rect != null
        serviceBridge?.updateOverlayCalibrationIndicator(rect != null)
        serviceBridge?.clearOverlayArrow()
    }

    fun setPreferManualCalibration(prefer: Boolean) {
        _preferManualCalibration.value = prefer
        serviceBridge?.updateOverlayCalibrationIndicator(_manualCalibration.value != null && prefer)
    }

    fun updateMidgameConfirmationSettings(
        castling: CastlingRights,
        enPassantSquare: String,
        activeSideOverride: PieceColor?,
        confirmed: Boolean
    ) {
        _configuredCastling.value = castling
        _configuredEnPassant.value = enPassantSquare.trim().lowercase()
        _activeColorOverride.value = activeSideOverride
        _midgameConfirmed.value = confirmed
        gameStateTracker.setUserConfirmedMidgameState(
            castlingRights = castling,
            enPassantSquare = enPassantSquare.trim().lowercase().takeIf { it.isNotBlank() && it != "-" },
            activeColorOverride = activeSideOverride
        )
        if (!confirmed) {
            gameStateTracker.resetAndResync(midgameConfirmed = false)
        }
    }

    fun resyncPositionState(confirmMidgameNow: Boolean = true) {
        _midgameConfirmed.value = confirmMidgameNow
        gameStateTracker.resetAndResync(midgameConfirmed = confirmMidgameNow)
        if (confirmMidgameNow) {
            gameStateTracker.setUserConfirmedMidgameState(
                castlingRights = _configuredCastling.value,
                enPassantSquare = _configuredEnPassant.value.takeIf { it.isNotBlank() && it != "-" },
                activeColorOverride = _activeColorOverride.value
            )
        }
        currentConfirmedFen = null
        serviceBridge?.clearOverlayArrow()
    }

    fun updateRuntimeStatus(updater: (ComponentRuntimeStatus) -> ComponentRuntimeStatus) {
        _runtimeStatus.update(updater)
    }

    /**
     * Preloads and verifies the offline YOLOv8m TFLite model and Stockfish 18 engine.
     */
    fun warmUpModelAndEngine() {
        scope.launch {
            val perf = _performanceMode.value
            val config = perf.resolveForDevice(
                availableProcessors = Runtime.getRuntime().availableProcessors(),
                sourceScreenWidth = 1080,
                sourceScreenHeight = 2400
            )
            try {
                val modelMeta = yoloDetector.ensureLoaded(config.visionCpuThreads)
                updateRuntimeStatus {
                    it.copy(
                        modelLoaded = true,
                        modelStatusText = "已就绪 (${modelMeta.assetName}, ${modelMeta.sizeBytes / 1024 / 1024}MB, ${config.visionCpuThreads}线程)",
                        modelDetailsText = "输入 ${modelMeta.inputTensor.shape} ${modelMeta.inputTensor.dataType} → 输出 ${modelMeta.outputTensor.shape} | SHA-256: ${modelMeta.sha256Hex.take(12)}…"
                    )
                }
            } catch (e: Exception) {
                updateRuntimeStatus {
                    it.copy(
                        modelLoaded = false,
                        modelStatusText = "加载失败: ${e.message}"
                    )
                }
            }

            try {
                val engineMeta = stockfishController.ensureStarted(config.engineThreads, config.engineHashMb)
                updateRuntimeStatus {
                    it.copy(
                        engineReady = true,
                        engineStatusText = "已就绪 (${engineMeta.engineName}, ${config.engineThreads}线程, ${config.engineHashMb}MB Hash)",
                        engineDetailsText = "NNUE: ${engineMeta.evalFileBig} & ${engineMeta.evalFileSmall}"
                    )
                }
            } catch (e: Exception) {
                updateRuntimeStatus {
                    it.copy(
                        engineReady = false,
                        engineStatusText = "启动失败: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Core single-frame analysis pipeline invoked when the user clicks the floating ♟ button.
     *
     * Can also be invoked directly in unit/integration tests with a captured [Bitmap].
     */
    suspend fun executeSingleFrameAnalysis(
        requestId: Long,
        capturedBitmap: Bitmap,
        captureToScreenTransform: CoordinateTransformer.CaptureToScreenTransform,
        effectiveConfig: PerformanceMode.EffectiveRuntimeConfig,
        onShowArrow: (com.example.model.OverlayArrowCoordinates) -> Unit,
        onClearArrow: () -> Unit,
        onUpdateButton: (OverlayButtonSymbol, String?) -> Unit
    ): AnalysisReport {
        val startMs = System.currentTimeMillis()
        val selectedSide = _activeColorOverride.value ?: _userColor.value
        val manualCal = _manualCalibration.value
        val manualCaptureRect = manualCal?.let {
            captureToScreenTransform.manualCalibrationToCaptureRect(it)
        }

        val visionStartMs = System.currentTimeMillis()
        val recognition = try {
            val meta = yoloDetector.ensureLoaded(effectiveConfig.visionCpuThreads)
            updateRuntimeStatus {
                it.copy(
                    modelLoaded = true,
                    modelStatusText = "已就绪 (${meta.assetName}, ${effectiveConfig.visionCpuThreads}线程)"
                )
            }
            yoloDetector.detectBoardAndPieces(
                captureBitmap = capturedBitmap,
                cpuThreads = effectiveConfig.visionCpuThreads,
                manualCalibrationCaptureRect = manualCaptureRect,
                preferManualCalibration = _preferManualCalibration.value && manualCaptureRect != null
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            onClearArrow()
            onUpdateButton(OverlayButtonSymbol.FAILED, "模型错误")
            val report = AnalysisReport(
                requestId = requestId,
                timestampMs = startMs,
                success = false,
                performanceMode = effectiveConfig.mode,
                analyzedColor = selectedSide,
                resolvedOrientation = null,
                orientationSource = "未执行",
                boardSource = "识别异常",
                detectedPiecesCount = 0,
                fen = null,
                fenStateProvenance = "未生成",
                bestMoveUci = null,
                moveDescription = null,
                moveSpecialType = null,
                promotionPieceLabel = null,
                evaluationText = null,
                visionLatencyMs = System.currentTimeMillis() - visionStartMs,
                engineLatencyMs = 0L,
                arrowOverlay = null,
                requiresUserConfirmation = false,
                errorOrWarningMessage = "视觉模型推理失败: ${e.message}"
            )
            _latestAnalysisReport.value = report
            return report
        }
        val visionLatencyMs = (System.currentTimeMillis() - visionStartMs).coerceAtLeast(1L)

        // Check if superseded by a newer request during vision inference
        if (requestId < stockfishController.currentLatestRequestId()) {
            val report = AnalysisReport(
                requestId = requestId,
                timestampMs = startMs,
                success = false,
                performanceMode = effectiveConfig.mode,
                analyzedColor = selectedSide,
                resolvedOrientation = null,
                orientationSource = "已取消",
                boardSource = recognition.boardSource,
                detectedPiecesCount = recognition.pieceDetections.size,
                fen = null,
                fenStateProvenance = "已被新请求取代",
                bestMoveUci = null,
                moveDescription = null,
                moveSpecialType = null,
                promotionPieceLabel = null,
                evaluationText = null,
                visionLatencyMs = visionLatencyMs,
                engineLatencyMs = 0L,
                arrowOverlay = null,
                errorOrWarningMessage = "旧分析请求 #$requestId 已丢弃"
            )
            return report
        }

        // Check recognition reliability & board localization
        val boardRect = recognition.boardCaptureRect
        if (!recognition.isReliable || boardRect == null) {
            onClearArrow()
            onUpdateButton(OverlayButtonSymbol.FAILED, "重试")
            val reason = buildString {
                append(recognition.unreliabilityReason ?: "棋盘或棋子识别不可靠，请重试")
                if (manualCal != null && manualCaptureRect == null) {
                    append("（注意：屏幕方向或分辨率已改变，原有手动校准区域已失效，请重新校准）")
                }
            }
            val report = AnalysisReport(
                requestId = requestId,
                timestampMs = startMs,
                success = false,
                performanceMode = effectiveConfig.mode,
                analyzedColor = selectedSide,
                resolvedOrientation = null,
                orientationSource = _boardOrientationMode.value.displayName,
                boardSource = recognition.boardSource,
                detectedPiecesCount = recognition.pieceDetections.size,
                fen = null,
                fenStateProvenance = "识别未通过可靠性校验",
                bestMoveUci = null,
                moveDescription = null,
                moveSpecialType = null,
                promotionPieceLabel = null,
                evaluationText = null,
                visionLatencyMs = visionLatencyMs,
                engineLatencyMs = 0L,
                arrowOverlay = null,
                requiresUserConfirmation = false,
                errorOrWarningMessage = reason
            )
            _latestAnalysisReport.value = report
            return report
        }

        // Step B: Resolve board orientation (NEVER assuming orientation merely from userColor!)
        val (resolvedOrientation, orientationSourceDesc) = when (_boardOrientationMode.value) {
            BoardOrientationMode.WHITE_BOTTOM -> Pair(
                BoardOrientation.WHITE_BOTTOM,
                "用户指定：白方在下"
            )
            BoardOrientationMode.BLACK_BOTTOM -> Pair(
                BoardOrientation.BLACK_BOTTOM,
                "用户指定：黑方在下"
            )
            BoardOrientationMode.AUTO -> {
                when (val inf = CoordinateTransformer.inferBoardOrientationFromGrid(recognition.gridPieces)) {
                    is CoordinateTransformer.OrientationInferenceResult.Determined -> Pair(
                        inf.orientation,
                        inf.reason
                    )
                    is CoordinateTransformer.OrientationInferenceResult.Ambiguous -> {
                        onClearArrow()
                        onUpdateButton(OverlayButtonSymbol.FAILED, "选方向")
                        val report = AnalysisReport(
                            requestId = requestId,
                            timestampMs = startMs,
                            success = false,
                            performanceMode = effectiveConfig.mode,
                            analyzedColor = selectedSide,
                            resolvedOrientation = null,
                            orientationSource = "自动方向不确定",
                            boardSource = recognition.boardSource,
                            detectedPiecesCount = recognition.pieceDetections.size,
                            fen = null,
                            fenStateProvenance = "待确认棋盘方向",
                            bestMoveUci = null,
                            moveDescription = null,
                            moveSpecialType = null,
                            promotionPieceLabel = null,
                            evaluationText = null,
                            visionLatencyMs = visionLatencyMs,
                            engineLatencyMs = 0L,
                            arrowOverlay = null,
                            requiresUserConfirmation = true,
                            errorOrWarningMessage = inf.reason
                        )
                        _latestAnalysisReport.value = report
                        return report
                    }
                }
            }
        }

        // Step C: Map 64-square visual grid to ChessBoardState and resolve full FEN + legality
        val boardState = ChessBoardState.fromVisualGrid(recognition.gridPieces, resolvedOrientation)
        val stateResolution = gameStateTracker.resolveRecognizedBoard(
            board = boardState,
            userColor = _userColor.value
        )

        val readyState = when (stateResolution) {
            is GameStateTracker.StateResolution.IllegalPosition -> {
                onClearArrow()
                onUpdateButton(OverlayButtonSymbol.FAILED, "非法")
                val report = AnalysisReport(
                    requestId = requestId,
                    timestampMs = startMs,
                    success = false,
                    performanceMode = effectiveConfig.mode,
                    analyzedColor = selectedSide,
                    resolvedOrientation = resolvedOrientation,
                    orientationSource = orientationSourceDesc,
                    boardSource = recognition.boardSource,
                    detectedPiecesCount = boardState.pieceCount(),
                    fen = boardState.toPiecePlacementFen(),
                    fenStateProvenance = "局面合法性校验未通过",
                    bestMoveUci = null,
                    moveDescription = null,
                    moveSpecialType = null,
                    promotionPieceLabel = null,
                    evaluationText = null,
                    visionLatencyMs = visionLatencyMs,
                    engineLatencyMs = 0L,
                    arrowOverlay = null,
                    requiresUserConfirmation = false,
                    errorOrWarningMessage = stateResolution.reason
                )
                _latestAnalysisReport.value = report
                return report
            }

            is GameStateTracker.StateResolution.RequiresMidgameConfirmation -> {
                onClearArrow()
                onUpdateButton(OverlayButtonSymbol.FAILED, "需确认")
                val report = AnalysisReport(
                    requestId = requestId,
                    timestampMs = startMs,
                    success = false,
                    performanceMode = effectiveConfig.mode,
                    analyzedColor = selectedSide,
                    resolvedOrientation = resolvedOrientation,
                    orientationSource = orientationSourceDesc,
                    boardSource = recognition.boardSource,
                    detectedPiecesCount = boardState.pieceCount(),
                    fen = stateResolution.candidatePosition.toFen(),
                    fenStateProvenance = "中途局面待确认易位/过路兵状态",
                    bestMoveUci = null,
                    moveDescription = null,
                    moveSpecialType = null,
                    promotionPieceLabel = null,
                    evaluationText = null,
                    visionLatencyMs = visionLatencyMs,
                    engineLatencyMs = 0L,
                    arrowOverlay = null,
                    requiresUserConfirmation = true,
                    errorOrWarningMessage = stateResolution.reason
                )
                _latestAnalysisReport.value = report
                return report
            }

            is GameStateTracker.StateResolution.RequiresResync -> {
                onClearArrow()
                onUpdateButton(OverlayButtonSymbol.FAILED, "需同步")
                val report = AnalysisReport(
                    requestId = requestId,
                    timestampMs = startMs,
                    success = false,
                    performanceMode = effectiveConfig.mode,
                    analyzedColor = selectedSide,
                    resolvedOrientation = resolvedOrientation,
                    orientationSource = orientationSourceDesc,
                    boardSource = recognition.boardSource,
                    detectedPiecesCount = boardState.pieceCount(),
                    fen = boardState.toPiecePlacementFen(),
                    fenStateProvenance = "连续局面无法用合法走法解释",
                    bestMoveUci = null,
                    moveDescription = null,
                    moveSpecialType = null,
                    promotionPieceLabel = null,
                    evaluationText = null,
                    visionLatencyMs = visionLatencyMs,
                    engineLatencyMs = 0L,
                    arrowOverlay = null,
                    requiresUserConfirmation = true,
                    errorOrWarningMessage = stateResolution.reason
                )
                _latestAnalysisReport.value = report
                return report
            }

            is GameStateTracker.StateResolution.Ready -> stateResolution
        }

        val confirmedFen = readyState.position.toFen()
        currentConfirmedFen = confirmedFen

        // Step D: Local Stockfish 18 Engine Analysis
        val engineStartMs = System.currentTimeMillis()
        val engineOutcome = stockfishController.analyzePosition(
            requestId = requestId,
            position = readyState.position,
            threads = effectiveConfig.engineThreads,
            hashMb = effectiveConfig.engineHashMb,
            searchTimeMs = effectiveConfig.searchTimeMs,
            confirmedFenValidator = { fen -> fen == currentConfirmedFen }
        )
        val engineLatencyMs = (System.currentTimeMillis() - engineStartMs).coerceAtLeast(1L)

        updateRuntimeStatus {
            it.copy(
                engineReady = stockfishController.isRunning(),
                engineStatusText = if (stockfishController.isRunning()) {
                    "已就绪 (Stockfish 18, ${effectiveConfig.engineThreads}线程, ${effectiveConfig.engineHashMb}MB)"
                } else {
                    it.engineStatusText
                }
            )
        }

        return when (engineOutcome) {
            is EngineAnalysisOutcome.BestMoveFound -> {
                val move = engineOutcome.bestMove
                val promoLabel = move.promotionLabelChinese()
                val arrowCoords = CoordinateTransformer.computeArrowOverlayCoordinates(
                    bestMoveUci = move.uci,
                    promotionLabel = promoLabel,
                    boardCaptureRect = boardRect,
                    orientation = resolvedOrientation,
                    captureToScreen = captureToScreenTransform
                )

                if (arrowCoords == null) {
                    onClearArrow()
                    onUpdateButton(OverlayButtonSymbol.FAILED, "坐标错误")
                    val report = AnalysisReport(
                        requestId = requestId,
                        timestampMs = startMs,
                        success = false,
                        performanceMode = effectiveConfig.mode,
                        analyzedColor = readyState.position.activeColor,
                        resolvedOrientation = resolvedOrientation,
                        orientationSource = orientationSourceDesc,
                        boardSource = recognition.boardSource,
                        detectedPiecesCount = boardState.pieceCount(),
                        fen = confirmedFen,
                        fenStateProvenance = readyState.provenanceDescription,
                        bestMoveUci = move.uci,
                        moveDescription = move.formatReadableDescription(),
                        moveSpecialType = move.specialType,
                        promotionPieceLabel = promoLabel,
                        evaluationText = engineOutcome.evaluationDisplay,
                        visionLatencyMs = visionLatencyMs,
                        engineLatencyMs = engineLatencyMs,
                        arrowOverlay = null,
                        errorOrWarningMessage = "无法将推荐走法 (${move.uci}) 映射到悬浮层坐标"
                    )
                    _latestAnalysisReport.value = report
                    report
                } else {
                    onShowArrow(arrowCoords)
                    val shortBadge = if (promoLabel != null) {
                        "${move.uci.uppercase()}"
                    } else {
                        "${move.uci.substring(0, 2)}→${move.uci.substring(2, 4)}"
                    }
                    onUpdateButton(OverlayButtonSymbol.SUCCESS, shortBadge)
                    val report = AnalysisReport(
                        requestId = requestId,
                        timestampMs = startMs,
                        success = true,
                        performanceMode = effectiveConfig.mode,
                        analyzedColor = readyState.position.activeColor,
                        resolvedOrientation = resolvedOrientation,
                        orientationSource = orientationSourceDesc,
                        boardSource = recognition.boardSource,
                        detectedPiecesCount = boardState.pieceCount(),
                        fen = confirmedFen,
                        fenStateProvenance = readyState.provenanceDescription,
                        bestMoveUci = move.uci,
                        moveDescription = move.formatReadableDescription(),
                        moveSpecialType = move.specialType,
                        promotionPieceLabel = promoLabel,
                        evaluationText = engineOutcome.evaluationDisplay,
                        visionLatencyMs = visionLatencyMs,
                        engineLatencyMs = engineOutcome.elapsedMs,
                        arrowOverlay = arrowCoords,
                        requiresUserConfirmation = false,
                        errorOrWarningMessage = null
                    )
                    _latestAnalysisReport.value = report
                    report
                }
            }

            is EngineAnalysisOutcome.TerminalPosition -> {
                onClearArrow()
                val badge = when (engineOutcome.terminalType) {
                    MoveSpecialType.CHECKMATE -> "将死"
                    MoveSpecialType.STALEMATE -> "逼和"
                    else -> "无走法"
                }
                onUpdateButton(OverlayButtonSymbol.FAILED, badge)
                val report = AnalysisReport(
                    requestId = requestId,
                    timestampMs = startMs,
                    success = false,
                    performanceMode = effectiveConfig.mode,
                    analyzedColor = readyState.position.activeColor,
                    resolvedOrientation = resolvedOrientation,
                    orientationSource = orientationSourceDesc,
                    boardSource = recognition.boardSource,
                    detectedPiecesCount = boardState.pieceCount(),
                    fen = confirmedFen,
                    fenStateProvenance = readyState.provenanceDescription,
                    bestMoveUci = null,
                    moveDescription = engineOutcome.description,
                    moveSpecialType = engineOutcome.terminalType,
                    promotionPieceLabel = null,
                    evaluationText = engineOutcome.terminalType.label,
                    visionLatencyMs = visionLatencyMs,
                    engineLatencyMs = engineLatencyMs,
                    arrowOverlay = null,
                    requiresUserConfirmation = false,
                    errorOrWarningMessage = engineOutcome.description
                )
                _latestAnalysisReport.value = report
                report
            }

            is EngineAnalysisOutcome.DiscardedStale -> {
                AnalysisReport(
                    requestId = requestId,
                    timestampMs = startMs,
                    success = false,
                    performanceMode = effectiveConfig.mode,
                    analyzedColor = readyState.position.activeColor,
                    resolvedOrientation = resolvedOrientation,
                    orientationSource = orientationSourceDesc,
                    boardSource = recognition.boardSource,
                    detectedPiecesCount = boardState.pieceCount(),
                    fen = confirmedFen,
                    fenStateProvenance = readyState.provenanceDescription,
                    bestMoveUci = null,
                    moveDescription = null,
                    moveSpecialType = null,
                    promotionPieceLabel = null,
                    evaluationText = null,
                    visionLatencyMs = visionLatencyMs,
                    engineLatencyMs = engineLatencyMs,
                    arrowOverlay = null,
                    requiresUserConfirmation = false,
                    errorOrWarningMessage = engineOutcome.reason
                )
            }

            is EngineAnalysisOutcome.Failed -> {
                onClearArrow()
                onUpdateButton(OverlayButtonSymbol.FAILED, "引擎错误")
                val report = AnalysisReport(
                    requestId = requestId,
                    timestampMs = startMs,
                    success = false,
                    performanceMode = effectiveConfig.mode,
                    analyzedColor = readyState.position.activeColor,
                    resolvedOrientation = resolvedOrientation,
                    orientationSource = orientationSourceDesc,
                    boardSource = recognition.boardSource,
                    detectedPiecesCount = boardState.pieceCount(),
                    fen = confirmedFen,
                    fenStateProvenance = readyState.provenanceDescription,
                    bestMoveUci = null,
                    moveDescription = null,
                    moveSpecialType = null,
                    promotionPieceLabel = null,
                    evaluationText = null,
                    visionLatencyMs = visionLatencyMs,
                    engineLatencyMs = engineLatencyMs,
                    arrowOverlay = null,
                    requiresUserConfirmation = false,
                    errorOrWarningMessage = engineOutcome.reason
                )
                _latestAnalysisReport.value = report
                report
            }
        }
    }

    /**
     * Cancels all tasks and releases capture, overlay, vision model, and Stockfish engine resources.
     * Can be cleanly restarted afterwards via "一键启动".
     */
    fun stopAllAndRelease() {
        activeAnalysisJob?.cancel()
        activeAnalysisJob = null
        stockfishController.cancelCurrentAnalysis()
        currentConfirmedFen = null
        gameStateTracker.resetAndResync(midgameConfirmed = _midgameConfirmed.value)

        serviceBridge?.stopServiceAndRelease()
        serviceBridge = null

        scope.launch {
            try {
                yoloDetector.release()
            } catch (_: Exception) {
            }
            try {
                stockfishController.stopEngine()
            } catch (_: Exception) {
            }
            updateRuntimeStatus {
                it.copy(
                    captureActive = false,
                    captureStatusText = "已停止 (可随时重新一键启动)",
                    overlayActive = false,
                    overlayStatusText = "已关闭",
                    modelLoaded = false,
                    modelStatusText = "已释放 (点击启动时自动加载)",
                    engineReady = false,
                    engineStatusText = "已停止 (点击启动时自动唤醒)",
                    overlayButtonSymbol = OverlayButtonSymbol.READY
                )
            }
        }
    }

    companion object {
        @Volatile
        private var instance: TrainerSessionOrchestrator? = null

        fun getInstance(context: Context): TrainerSessionOrchestrator {
            return instance ?: synchronized(this) {
                instance ?: TrainerSessionOrchestrator(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
