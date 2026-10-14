package com.example.vision

import android.content.Context
import android.graphics.Bitmap
import com.example.model.ChessPiece
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class RawDetection(
    val classId: Int,
    val confidence: Float,
    val modelBox: CoordinateTransformer.RectFBox,
    val captureBox: CoordinateTransformer.RectFBox
)

data class SquareConflict(
    val gridRow: Int,
    val gridCol: Int,
    val primaryPiece: ChessPiece,
    val primaryConfidence: Float,
    val conflictingPiece: ChessPiece,
    val conflictingConfidence: Float
)

data class BoardRecognitionResult(
    val boardCaptureRect: CoordinateTransformer.RectFBox?,
    val boardConfidence: Float,
    val boardSource: String,
    val gridPieces: Array<Array<ChessPiece?>>,
    val gridConfidences: Array<FloatArray>,
    val pieceDetections: List<RawDetection>,
    val conflicts: List<SquareConflict>,
    val lowConfidenceSquares: List<Pair<Int, Int>>,
    val letterboxTransform: CoordinateTransformer.LetterboxTransform,
    val isReliable: Boolean,
    val unreliabilityReason: String?
)

interface RawYoloInferenceBackend : AutoCloseable {
    val modelMetadata: VerifiedTfliteModelMetadata
    val activeThreads: Int
    fun runInference(inputBuffer: ByteBuffer, outputArray: Array<Array<FloatArray>>)
}

class AndroidLiteRtBackend(
    private val modelBuffer: ByteBuffer,
    override val modelMetadata: VerifiedTfliteModelMetadata,
    override val activeThreads: Int
) : RawYoloInferenceBackend {

    private val interpreter: Interpreter

    init {
        val options = Interpreter.Options().apply {
            setNumThreads(activeThreads.coerceIn(1, 8))
            setUseNNAPI(false)
        }
        modelBuffer.rewind()
        interpreter = Interpreter(modelBuffer, options)

        val inTensor = interpreter.getInputTensor(0)
        val outTensor = interpreter.getOutputTensor(0)
        val runtimeInputDesc = TensorDescriptor(
            index = 0,
            name = inTensor.name(),
            shape = inTensor.shape().toList(),
            dataType = inTensor.dataType().toStandardName()
        )
        val runtimeOutputDesc = TensorDescriptor(
            index = 0,
            name = outTensor.name(),
            shape = outTensor.shape().toList(),
            dataType = outTensor.dataType().toStandardName()
        )
        TfliteModelInspector.validateTensorCompatibility(runtimeInputDesc, runtimeOutputDesc)
    }

    override fun runInference(inputBuffer: ByteBuffer, outputArray: Array<Array<FloatArray>>) {
        inputBuffer.rewind()
        interpreter.run(inputBuffer, outputArray)
    }

    override fun close() {
        interpreter.close()
    }

    private fun DataType.toStandardName(): String = when (this) {
        DataType.FLOAT32 -> "FLOAT32"
        DataType.INT32 -> "INT32"
        DataType.UINT8 -> "UINT8"
        DataType.INT64 -> "INT64"
        DataType.STRING -> "STRING"
        DataType.BOOL -> "BOOL"
        DataType.INT16 -> "INT16"
        DataType.INT8 -> "INT8"
    }
}

/**
 * Local YOLOv8m chess piece and board detector backed by `best_f16.tflite`.
 *
 * - Loads and validates the model once, reusing the interpreter across requests.
 * - Serializes all inference calls via [inferenceMutex] so the non-thread-safe interpreter
 *   is never accessed concurrently.
 * - Implements exact 640x640 RGB letterbox preprocessing, NMS postprocessing, board selection,
 *   focused crop inference for calibrated/mobile portrait chessboards, and 64-square piece mapping
 *   with conflict/uncertainty detection.
 */
class YoloChessDetector(
    private val context: Context? = null,
    private val backendFactory: ((ByteBuffer, VerifiedTfliteModelMetadata, Int) -> RawYoloInferenceBackend)? = null
) {
    private val inferenceMutex = Mutex()
    private var cachedModelBuffer: ByteBuffer? = null
    private var verifiedMetadata: VerifiedTfliteModelMetadata? = null
    private var activeBackend: RawYoloInferenceBackend? = null

    private val reusableInputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(1 * 640 * 640 * 3 * 4).order(ByteOrder.nativeOrder())
    private val reusablePixels = IntArray(640 * 640)
    private val reusableOutputArray: Array<Array<FloatArray>> =
        Array(1) { Array(17) { FloatArray(8400) } }

    fun getVerifiedMetadata(): VerifiedTfliteModelMetadata? = verifiedMetadata

    fun isLoaded(): Boolean = activeBackend != null && verifiedMetadata != null

    suspend fun ensureLoaded(cpuThreads: Int): VerifiedTfliteModelMetadata = inferenceMutex.withLock {
        EnsureLoadedInternal(cpuThreads)
    }

    private fun EnsureLoadedInternal(cpuThreads: Int): VerifiedTfliteModelMetadata {
        val clampedThreads = cpuThreads.coerceIn(1, 8)
        val currentBackend = activeBackend
        val currentMeta = verifiedMetadata
        if (currentBackend != null && currentMeta != null && currentBackend.activeThreads == clampedThreads) {
            return currentMeta
        }

        currentBackend?.close()
        activeBackend = null

        val modelBuf = cachedModelBuffer ?: loadModelByteBuffer().also {
            cachedModelBuffer = it
        }
        val meta = verifiedMetadata ?: TfliteModelInspector.inspectAndValidate(
            rawBuffer = modelBuf,
            assetName = TfliteModelInspector.EXPECTED_MODEL_ASSET,
            verifySha256 = true
        ).also {
            verifiedMetadata = it
        }

        val factory = backendFactory ?: { buf, m, threads ->
            AndroidLiteRtBackend(buf, m, threads)
        }
        activeBackend = factory(modelBuf, meta, clampedThreads)
        return meta
    }

    private fun loadModelByteBuffer(): ByteBuffer {
        val ctx = context ?: throw ModelCompatibilityException("Context 未提供，无法从 assets 加载模型")
        return try {
            ctx.assets.openFd(TfliteModelInspector.EXPECTED_MODEL_ASSET).use { afd ->
                FileInputStream(afd.fileDescriptor).channel.use { channel ->
                    channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
                }
            }
        } catch (_: Exception) {
            // Fallback if asset was compressed or read from stream
            ctx.assets.open(TfliteModelInspector.EXPECTED_MODEL_ASSET).use { input ->
                val bytes = input.readBytes()
                val direct = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN)
                direct.put(bytes)
                direct.rewind()
                direct
            }
        }
    }

    /**
     * Detects the 8x8 chessboard rectangle in capture frame coordinates for the in-game
     * "AI自动贴合" calibration assistant. Prioritizes deterministic 8x8 checkerboard grid
     * detection on mobile screens and cross-checks with YOLOv8 board detection.
     */
    suspend fun detectBoardRectForCalibration(
        captureBitmap: Bitmap,
        cpuThreads: Int
    ): CoordinateTransformer.RectFBox? = inferenceMutex.withLock {
        val w = captureBitmap.width
        val h = captureBitmap.height
        val fullPixels = IntArray(w * h)
        captureBitmap.getPixels(fullPixels, 0, w, 0, 0, w, h)

        val checkerboardRect = CoordinateTransformer.detectCheckerboardRectFromPixels(fullPixels, w, h)
        if (checkerboardRect != null && checkerboardRect.isValid(80f)) {
            return@withLock checkerboardRect
        }

        return@withLock try {
            EnsureLoadedInternal(cpuThreads)
            val backend = activeBackend ?: return@withLock checkerboardRect
            val letterbox = preprocessBitmapToBuffer(captureBitmap, reusableInputBuffer, reusablePixels)
            for (c in 0 until 17) {
                reusableOutputArray[0][c].fill(0f)
            }
            backend.runInference(reusableInputBuffer, reusableOutputArray)
            val res = decodeAndPostprocess(
                rawOutput = reusableOutputArray[0],
                letterbox = letterbox
            )
            res.boardCaptureRect ?: checkerboardRect
        } catch (_: Exception) {
            checkerboardRect
        }
    }

    /**
     * Runs serialized letterbox preprocessing + YOLOv8 inference + NMS + 64-square board mapping.
     *
     * When the user has calibrated the chessboard in the game interface ([preferManualCalibration]
     * with a valid [manualCalibrationCaptureRect]) on a tall portrait phone screen, runs focused
     * 640x640 inference on a padded crop around the calibrated chessboard so every square and piece
     * has maximum resolution in the 640x640 model tensor.
     */
    suspend fun detectBoardAndPieces(
        captureBitmap: Bitmap,
        cpuThreads: Int,
        manualCalibrationCaptureRect: CoordinateTransformer.RectFBox? = null,
        preferManualCalibration: Boolean = false
    ): BoardRecognitionResult = inferenceMutex.withLock {
        EnsureLoadedInternal(cpuThreads)
        val backend = activeBackend
            ?: throw ModelCompatibilityException("视觉模型未初始化")

        val fullW = captureBitmap.width
        val fullH = captureBitmap.height

        // 1. If the user explicitly calibrated the chessboard in the game interface and the
        // calibrated region occupies only a sub-part of the screen (e.g. portrait mobile game UI),
        // run focused crop inference around the calibrated chessboard first!
        if (preferManualCalibration && manualCalibrationCaptureRect != null &&
            manualCalibrationCaptureRect.isValid(48f) &&
            (manualCalibrationCaptureRect.height < fullH * 0.85f || manualCalibrationCaptureRect.width < fullW * 0.85f)
        ) {
            val croppedResult = runCroppedBoardInference(
                backend = backend,
                captureBitmap = captureBitmap,
                targetBoardRect = manualCalibrationCaptureRect,
                boardSourceLabel = "手动校准区域 (游戏界面高精度识别)"
            )
            if (croppedResult != null && (croppedResult.isReliable || croppedResult.pieceDetections.size >= 4)) {
                return@withLock croppedResult
            }
        }

        // 2. Standard full-frame inference
        val letterbox = preprocessBitmapToBuffer(captureBitmap, reusableInputBuffer, reusablePixels)
        for (c in 0 until 17) {
            reusableOutputArray[0][c].fill(0f)
        }
        backend.runInference(reusableInputBuffer, reusableOutputArray)

        val fullFrameResult = decodeAndPostprocess(
            rawOutput = reusableOutputArray[0],
            letterbox = letterbox,
            manualCalibrationCaptureRect = manualCalibrationCaptureRect,
            preferManualCalibration = preferManualCalibration
        )

        if (fullFrameResult.isReliable) {
            return@withLock fullFrameResult
        }

        // 3. Fallback for tall portrait phone screens (e.g. Chess.com 1080x2340) where full-screen
        // letterbox shrank the board too much or missed edge-to-edge full-width board borders:
        val fallbackBoardRect = when {
            manualCalibrationCaptureRect != null && manualCalibrationCaptureRect.isValid(48f) ->
                manualCalibrationCaptureRect
            fullFrameResult.boardCaptureRect != null && fullFrameResult.boardCaptureRect.isValid(48f) ->
                fullFrameResult.boardCaptureRect
            fullH > fullW * 1.25f -> {
                val fullPixels = IntArray(fullW * fullH)
                captureBitmap.getPixels(fullPixels, 0, fullW, 0, 0, fullW, fullH)
                CoordinateTransformer.detectCheckerboardRectFromPixels(fullPixels, fullW, fullH)
            }
            else -> null
        }

        if (fallbackBoardRect != null &&
            (fallbackBoardRect.height < fullH * 0.88f || fallbackBoardRect.width < fullW * 0.88f)
        ) {
            val sourceLabel = if (manualCalibrationCaptureRect != null) {
                "手动校准区域 (局部增强识别)"
            } else {
                "8×8 棋盘网格自动定位 (局部增强识别)"
            }
            val croppedFallback = runCroppedBoardInference(
                backend = backend,
                captureBitmap = captureBitmap,
                targetBoardRect = fallbackBoardRect,
                boardSourceLabel = sourceLabel
            )
            if (croppedFallback != null &&
                (croppedFallback.isReliable || croppedFallback.pieceDetections.size > fullFrameResult.pieceDetections.size)
            ) {
                return@withLock croppedFallback
            }
        }

        return@withLock fullFrameResult
    }

    private fun runCroppedBoardInference(
        backend: RawYoloInferenceBackend,
        captureBitmap: Bitmap,
        targetBoardRect: CoordinateTransformer.RectFBox,
        boardSourceLabel: String
    ): BoardRecognitionResult? {
        val fullW = captureBitmap.width
        val fullH = captureBitmap.height
        val padX = (targetBoardRect.width * 0.03f).roundToInt()
        val padY = (targetBoardRect.height * 0.03f).roundToInt()

        val cropLeft = (targetBoardRect.left.roundToInt() - padX).coerceIn(0, fullW - 32)
        val cropTop = (targetBoardRect.top.roundToInt() - padY).coerceIn(0, fullH - 32)
        val cropRight = (targetBoardRect.right.roundToInt() + padX).coerceIn(cropLeft + 32, fullW)
        val cropBottom = (targetBoardRect.bottom.roundToInt() + padY).coerceIn(cropTop + 32, fullH)
        val cropW = cropRight - cropLeft
        val cropH = cropBottom - cropTop
        if (cropW < 64 || cropH < 64) return null

        val letterbox = preprocessSubRegionToBuffer(
            sourceBitmap = captureBitmap,
            cropLeft = cropLeft,
            cropTop = cropTop,
            cropWidth = cropW,
            cropHeight = cropH,
            targetBuffer = reusableInputBuffer
        )

        for (c in 0 until 17) {
            reusableOutputArray[0][c].fill(0f)
        }
        backend.runInference(reusableInputBuffer, reusableOutputArray)

        val decoded = decodeAndPostprocess(
            rawOutput = reusableOutputArray[0],
            letterbox = letterbox,
            manualCalibrationCaptureRect = targetBoardRect,
            preferManualCalibration = true
        )
        return decoded.copy(boardSource = boardSourceLabel)
    }

    suspend fun release() = inferenceMutex.withLock {
        activeBackend?.close()
        activeBackend = null
    }

    companion object {
        const val BOARD_CONF_THRESHOLD = 0.45f
        const val PIECE_CONF_THRESHOLD = 0.35f
        const val NMS_IOU_THRESHOLD = 0.45f
        const val CROSS_CLASS_SUPPRESS_IOU = 0.72f
        const val LOW_CONFIDENCE_WARNING_THRESHOLD = 0.45f
        const val LETTERBOX_PAD_GRAY = 114

        /**
         * Resamples [sourceBitmap] into a 640x640 RGB letterboxed buffer normalized to [0f..1f]
         * with padding RGB(114, 114, 114), returning the exact [CoordinateTransformer.LetterboxTransform].
         */
        fun preprocessBitmapToBuffer(
            sourceBitmap: Bitmap,
            targetBuffer: ByteBuffer,
            scratchPixels: IntArray = IntArray(640 * 640)
        ): CoordinateTransformer.LetterboxTransform {
            return preprocessSubRegionToBuffer(
                sourceBitmap = sourceBitmap,
                cropLeft = 0,
                cropTop = 0,
                cropWidth = sourceBitmap.width,
                cropHeight = sourceBitmap.height,
                targetBuffer = targetBuffer
            )
        }

        /**
         * Resamples a sub-region `[cropLeft, cropTop, cropWidth, cropHeight]` of [sourceBitmap]
         * into a 640x640 RGB letterboxed buffer normalized to [0f..1f], recording the exact
         * `(cropLeft, cropTop)` offset in [CoordinateTransformer.LetterboxTransform] so all output
         * bounding boxes map directly back to full [sourceBitmap] capture coordinates.
         */
        fun preprocessSubRegionToBuffer(
            sourceBitmap: Bitmap,
            cropLeft: Int,
            cropTop: Int,
            cropWidth: Int,
            cropHeight: Int,
            targetBuffer: ByteBuffer
        ): CoordinateTransformer.LetterboxTransform {
            val fullW = sourceBitmap.width
            val fullH = sourceBitmap.height
            val letterbox = CoordinateTransformer.computeLetterbox(
                captureWidth = cropWidth,
                captureHeight = cropHeight,
                modelSize = 640,
                cropOffsetX = cropLeft,
                cropOffsetY = cropTop,
                fullCaptureWidth = fullW,
                fullCaptureHeight = fullH
            )

            val srcPixels = IntArray(cropWidth * cropHeight)
            sourceBitmap.getPixels(srcPixels, 0, cropWidth, cropLeft, cropTop, cropWidth, cropHeight)

            val padFloat = LETTERBOX_PAD_GRAY / 255.0f
            val inv255 = 1.0f / 255.0f
            val padLeftInt = letterbox.padLeft.toInt()
            val padTopInt = letterbox.padTop.toInt()
            val resW = letterbox.resizedWidth
            val resH = letterbox.resizedHeight
            val rightLimit = padLeftInt + resW
            val bottomLimit = padTopInt + resH

            val scaleX = cropWidth.toFloat() / resW.toFloat()
            val scaleY = cropHeight.toFloat() / resH.toFloat()

            targetBuffer.rewind()
            for (y in 0 until 640) {
                if (y < padTopInt || y >= bottomLimit) {
                    for (x in 0 until 640) {
                        targetBuffer.putFloat(padFloat)
                        targetBuffer.putFloat(padFloat)
                        targetBuffer.putFloat(padFloat)
                    }
                    continue
                }

                val srcYf = ((y - padTopInt + 0.5f) * scaleY - 0.5f).coerceIn(0f, (cropHeight - 1).toFloat())
                val y0 = srcYf.toInt()
                val y1 = min(y0 + 1, cropHeight - 1)
                val wy = srcYf - y0
                val wy0 = 1.0f - wy
                val row0Offset = y0 * cropWidth
                val row1Offset = y1 * cropWidth

                for (x in 0 until 640) {
                    if (x < padLeftInt || x >= rightLimit) {
                        targetBuffer.putFloat(padFloat)
                        targetBuffer.putFloat(padFloat)
                        targetBuffer.putFloat(padFloat)
                    } else {
                        val srcXf = ((x - padLeftInt + 0.5f) * scaleX - 0.5f).coerceIn(0f, (cropWidth - 1).toFloat())
                        val x0 = srcXf.toInt()
                        val x1 = min(x0 + 1, cropWidth - 1)
                        val wx = srcXf - x0
                        val wx0 = 1.0f - wx

                        val c00 = srcPixels[row0Offset + x0]
                        val c01 = srcPixels[row0Offset + x1]
                        val c10 = srcPixels[row1Offset + x0]
                        val c11 = srcPixels[row1Offset + x1]

                        val w00 = wy0 * wx0
                        val w01 = wy0 * wx
                        val w10 = wy * wx0
                        val w11 = wy * wx

                        val r = (((c00 shr 16) and 0xFF) * w00 +
                            ((c01 shr 16) and 0xFF) * w01 +
                            ((c10 shr 16) and 0xFF) * w10 +
                            ((c11 shr 16) and 0xFF) * w11) * inv255

                        val g = (((c00 shr 8) and 0xFF) * w00 +
                            ((c01 shr 8) and 0xFF) * w01 +
                            ((c10 shr 8) and 0xFF) * w10 +
                            ((c11 shr 8) and 0xFF) * w11) * inv255

                        val b = ((c00 and 0xFF) * w00 +
                            (c01 and 0xFF) * w01 +
                            (c10 and 0xFF) * w10 +
                            (c11 and 0xFF) * w11) * inv255

                        targetBuffer.putFloat(r)
                        targetBuffer.putFloat(g)
                        targetBuffer.putFloat(b)
                    }
                }
            }
            targetBuffer.rewind()
            return letterbox
        }

        /**
         * Decodes YOLOv8 `[17, 8400]` output tensor, runs NMS, selects the chessboard,
         * and maps pieces to the 64 squares with conflict and reliability checks.
         */
        fun decodeAndPostprocess(
            rawOutput: Array<FloatArray>,
            letterbox: CoordinateTransformer.LetterboxTransform,
            manualCalibrationCaptureRect: CoordinateTransformer.RectFBox? = null,
            preferManualCalibration: Boolean = false
        ): BoardRecognitionResult {
            if (rawOutput.size != 17 || rawOutput[0].size != 8400) {
                throw ModelCompatibilityException(
                    "输出张量尺寸异常: [${rawOutput.size}, ${rawOutput.firstOrNull()?.size}] (预期 [17, 8400])"
                )
            }

            // Determine whether coordinates are normalized [0..1] or already in [0..640]
            var maxCoordSample = 0f
            for (i in 0 until 8400 step 37) {
                maxCoordSample = max(maxCoordSample, max(rawOutput[0][i], rawOutput[2][i]))
            }
            val coordMultiplier = if (maxCoordSample <= 2.0f) 640.0f else 1.0f

            val boardCandidates = ArrayList<RawDetection>()
            val pieceCandidates = ArrayList<RawDetection>()

            for (anchor in 0 until 8400) {
                var bestClass = -1
                var bestScore = 0f
                for (c in 0 until ChessPiece.NUM_MODEL_CLASSES) {
                    val sc = rawOutput[4 + c][anchor]
                    if (sc > bestScore) {
                        bestScore = sc
                        bestClass = c
                    }
                }

                val threshold = if (bestClass == ChessPiece.CLASS_ID_BOARD) {
                    BOARD_CONF_THRESHOLD
                } else {
                    PIECE_CONF_THRESHOLD
                }
                if (bestScore < threshold) continue

                val cx = rawOutput[0][anchor] * coordMultiplier
                val cy = rawOutput[1][anchor] * coordMultiplier
                val bw = rawOutput[2][anchor] * coordMultiplier
                val bh = rawOutput[3][anchor] * coordMultiplier
                if (bw <= 2f || bh <= 2f) continue

                val mx1 = cx - bw * 0.5f
                val my1 = cy - bh * 0.5f
                val mx2 = cx + bw * 0.5f
                val my2 = cy + bh * 0.5f

                val modelBox = CoordinateTransformer.RectFBox(mx1, my1, mx2, my2)
                val captureBox = letterbox.modelBoxToCaptureBox(mx1, my1, mx2, my2)
                val det = RawDetection(
                    classId = bestClass,
                    confidence = bestScore,
                    modelBox = modelBox,
                    captureBox = captureBox
                )
                if (bestClass == ChessPiece.CLASS_ID_BOARD) {
                    boardCandidates.add(det)
                } else {
                    pieceCandidates.add(det)
                }
            }

            val nmsBoards = runNms(boardCandidates, NMS_IOU_THRESHOLD, sameClassOnly = true)
            val nmsPieces = runPieceNms(pieceCandidates)

            // Select board region
            val (selectedBoardRect, boardConf, boardSource) = selectBoardRect(
                nmsBoards = nmsBoards,
                nmsPieces = nmsPieces,
                manualRect = manualCalibrationCaptureRect,
                preferManual = preferManualCalibration
            )

            val gridPieces = Array(8) { arrayOfNulls<ChessPiece>(8) }
            val gridConfs = Array(8) { FloatArray(8) }

            if (selectedBoardRect == null || !selectedBoardRect.isValid(48f)) {
                return BoardRecognitionResult(
                    boardCaptureRect = null,
                    boardConfidence = 0f,
                    boardSource = boardSource,
                    gridPieces = gridPieces,
                    gridConfidences = gridConfs,
                    pieceDetections = nmsPieces,
                    conflicts = emptyList(),
                    lowConfidenceSquares = emptyList(),
                    letterboxTransform = letterbox,
                    isReliable = false,
                    unreliabilityReason = "未能定位有效棋盘区域，请点击悬浮窗上的「⊞ 校准」在游戏界面对齐棋盘外框"
                )
            }

            val conflicts = ArrayList<SquareConflict>()
            val lowConfSquares = ArrayList<Pair<Int, Int>>()
            val inBoardPieces = ArrayList<RawDetection>()

            // Sort pieces by confidence descending so highest-confidence pieces claim squares first
            val sortedPieces = nmsPieces.sortedByDescending { it.confidence }
            for (det in sortedPieces) {
                val piece = ChessPiece.fromYoloClassId(det.classId) ?: continue
                // Use horizontal center and slightly lower-center (55% down box) to avoid tall crown drift
                val anchorX = det.captureBox.centerX
                val anchorY = det.captureBox.top + det.captureBox.height * 0.55f
                val cell = CoordinateTransformer.mapCapturePointToGridCell(
                    px = anchorX,
                    py = anchorY,
                    boardCaptureRect = selectedBoardRect
                ) ?: continue

                inBoardPieces.add(det)
                val (row, col) = cell
                val existing = gridPieces[row][col]
                val existingConf = gridConfs[row][col]

                if (existing == null) {
                    gridPieces[row][col] = piece
                    gridConfs[row][col] = det.confidence
                    if (det.confidence < LOW_CONFIDENCE_WARNING_THRESHOLD) {
                        lowConfSquares.add(Pair(row, col))
                    }
                } else if (existing == piece) {
                    // Duplicate detection of the same piece class in the same square: keep higher score
                    gridConfs[row][col] = max(existingConf, det.confidence)
                } else {
                    // Different piece class detected in the same square!
                    // Only ignore if the secondary detection is weak background noise and far below primary
                    val isWeakNoise = det.confidence < 0.42f && (existingConf - det.confidence) >= 0.35f
                    if (!isWeakNoise) {
                        conflicts.add(
                            SquareConflict(
                                gridRow = row,
                                gridCol = col,
                                primaryPiece = existing,
                                primaryConfidence = existingConf,
                                conflictingPiece = piece,
                                conflictingConfidence = det.confidence
                            )
                        )
                    }
                }
            }

            var totalPlaced = 0
            var confSum = 0f
            for (r in 0..7) {
                for (c in 0..7) {
                    if (gridPieces[r][c] != null) {
                        totalPlaced++
                        confSum += gridConfs[r][c]
                    }
                }
            }
            val avgConf = if (totalPlaced > 0) confSum / totalPlaced else 0f

            val unreliabilityReason = when {
                conflicts.isNotEmpty() -> {
                    val first = conflicts.first()
                    "检测到同格冲突 (第${first.gridRow + 1}行第${first.gridCol + 1}列同时识别为 ${first.primaryPiece.labelName}[${"%.2f".format(first.primaryConfidence)}] 与 ${first.conflictingPiece.labelName}[${"%.2f".format(first.conflictingConfidence)}])，结果不可靠，请重试"
                }
                totalPlaced < 2 -> "棋盘内仅识别到 $totalPlaced 枚棋子，画面可能被遮挡或非国际象棋棋盘，可点击悬浮窗「⊞ 校准」对齐棋盘"
                avgConf < 0.45f -> "棋子平均识别置信度过低 (${"%.2f".format(avgConf)})，画面模糊或风格不匹配，请重试"
                lowConfSquares.size > 3 -> "存在 ${lowConfSquares.size} 个低置信度棋子格，识别结果不可靠，请重试"
                else -> null
            }

            return BoardRecognitionResult(
                boardCaptureRect = selectedBoardRect,
                boardConfidence = boardConf,
                boardSource = boardSource,
                gridPieces = gridPieces,
                gridConfidences = gridConfs,
                pieceDetections = inBoardPieces,
                conflicts = conflicts,
                lowConfidenceSquares = lowConfSquares,
                letterboxTransform = letterbox,
                isReliable = unreliabilityReason == null,
                unreliabilityReason = unreliabilityReason
            )
        }

        private fun selectBoardRect(
            nmsBoards: List<RawDetection>,
            nmsPieces: List<RawDetection>,
            manualRect: CoordinateTransformer.RectFBox?,
            preferManual: Boolean
        ): Triple<CoordinateTransformer.RectFBox?, Float, String> {
            if (preferManual && manualRect != null && manualRect.isValid(48f)) {
                return Triple(manualRect, 1.0f, "手动校准区域 (优先)")
            }

            // Filter boards that have plausible square aspect ratio (0.72..1.38)
            val validAutoBoards = nmsBoards.filter { b ->
                val rect = b.captureBox
                rect.isValid(48f) && (rect.width / rect.height) in 0.72f..1.38f
            }

            if (validAutoBoards.isNotEmpty()) {
                // Score each candidate board by how many detected pieces fall inside it and its confidence
                val bestBoard = validAutoBoards.maxByOrNull { board ->
                    val insideCount = nmsPieces.count { p ->
                        val cx = p.captureBox.centerX
                        val cy = p.captureBox.centerY
                        cx in board.captureBox.left..board.captureBox.right &&
                            cy in board.captureBox.top..board.captureBox.bottom
                    }
                    // Prefer the first/leftmost board on ties when piece count & confidence are similar
                    insideCount * 10f + board.confidence * 5f - (board.captureBox.left * 0.0001f)
                }!!
                return Triple(bestBoard.captureBox, bestBoard.confidence, "YOLOv8 自动定位 (conf=${"%.2f".format(bestBoard.confidence)})")
            }

            if (manualRect != null && manualRect.isValid(48f)) {
                return Triple(manualRect, 1.0f, "手动校准区域 (自动定位未命中回退)")
            }

            return Triple(null, 0f, "未检测到棋盘")
        }

        fun computeIoU(
            a: CoordinateTransformer.RectFBox,
            b: CoordinateTransformer.RectFBox
        ): Float {
            val x1 = max(a.left, b.left)
            val y1 = max(a.top, b.top)
            val x2 = min(a.right, b.right)
            val y2 = min(a.bottom, b.bottom)
            val interW = max(0f, x2 - x1)
            val interH = max(0f, y2 - y1)
            val interArea = interW * interH
            if (interArea <= 0f) return 0f
            val unionArea = a.area + b.area - interArea
            return if (unionArea > 1e-6f) interArea / unionArea else 0f
        }

        fun runNms(
            candidates: List<RawDetection>,
            iouThreshold: Float,
            sameClassOnly: Boolean
        ): List<RawDetection> {
            if (candidates.isEmpty()) return emptyList()
            val sorted = candidates.sortedByDescending { it.confidence }
            val kept = ArrayList<RawDetection>()
            for (cand in sorted) {
                var suppressed = false
                for (k in kept) {
                    if (sameClassOnly && cand.classId != k.classId) continue
                    if (computeIoU(cand.modelBox, k.modelBox) >= iouThreshold) {
                        suppressed = true
                        break
                    }
                }
                if (!suppressed) {
                    kept.add(cand)
                }
            }
            return kept
        }

        /**
         * Class-aware NMS for chess pieces that suppresses duplicate anchor boxes for the same
         * piece while preserving genuine same-square conflicting predictions when their confidences
         * are close so that [decodeAndPostprocess] can detect and report same-square ambiguity.
         */
        private fun runPieceNms(candidates: List<RawDetection>): List<RawDetection> {
            if (candidates.isEmpty()) return emptyList()
            val sorted = candidates.sortedByDescending { it.confidence }
            val kept = ArrayList<RawDetection>()
            for (cand in sorted) {
                var suppressed = false
                for (k in kept) {
                    val iou = computeIoU(cand.modelBox, k.modelBox)
                    if (cand.classId == k.classId && iou >= NMS_IOU_THRESHOLD) {
                        suppressed = true
                        break
                    }
                    // If different class at virtually the exact same box (IoU >= 0.72) AND the weaker
                    // prediction is much lower confidence (>= 0.35 gap), suppress the weak ghost box.
                    if (cand.classId != k.classId && iou >= CROSS_CLASS_SUPPRESS_IOU &&
                        (k.confidence - cand.confidence) >= 0.35f
                    ) {
                        suppressed = true
                        break
                    }
                }
                if (!suppressed) {
                    kept.add(cand)
                }
            }
            return kept
        }
    }
}
