package com.example.vision

import com.example.model.BoardOrientation
import com.example.model.ChessPiece
import com.example.model.ManualCalibrationNormalizedRect
import com.example.model.OverlayArrowCoordinates
import com.example.model.PieceColor
import com.example.model.PieceType
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Unified coordinate transformation pipeline:
 *
 * Model Space (640x640 letterboxed)
 *   <-> Capture Frame Space (captureWidth x captureHeight)
 *   <-> Chessboard 8x8 Grid Space (gridRow 0..7 top-to-bottom, gridCol 0..7 left-to-right)
 *   <-> Chess Algebraic Square (file 0..7 'a'..'h', rank 0..7 '1'..'8')
 *   <-> Floating Overlay Window Space (overlayWidth x overlayHeight with window offset)
 */
object CoordinateTransformer {

    const val MODEL_INPUT_SIZE = 640

    data class RectFBox(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val width: Float get() = (right - left).coerceAtLeast(0f)
        val height: Float get() = (bottom - top).coerceAtLeast(0f)
        val centerX: Float get() = (left + right) * 0.5f
        val centerY: Float get() = (top + bottom) * 0.5f
        val area: Float get() = width * height

        fun isValid(minSize: Float = 8f): Boolean = width >= minSize && height >= minSize
    }

    /**
     * Exact letterbox parameters used to fit [captureWidth] x [captureHeight] into
     * [modelSize] x [modelSize] (default 640x640) while preserving aspect ratio.
     * Supports an optional sub-region crop offset ([cropOffsetX], [cropOffsetY]) in the
     * full capture frame when running focused inference on a calibrated or localized board.
     */
    data class LetterboxTransform(
        val captureWidth: Int,
        val captureHeight: Int,
        val modelSize: Int = MODEL_INPUT_SIZE,
        val scale: Float,
        val resizedWidth: Int,
        val resizedHeight: Int,
        val padLeft: Float,
        val padTop: Float,
        val cropOffsetX: Int = 0,
        val cropOffsetY: Int = 0,
        val fullCaptureWidth: Int = captureWidth,
        val fullCaptureHeight: Int = captureHeight
    ) {
        /**
         * Maps a bounding box from 640x640 letterboxed model coordinates back to
         * full capture frame pixel coordinates.
         */
        fun modelBoxToCaptureBox(
            modelLeft: Float,
            modelTop: Float,
            modelRight: Float,
            modelBottom: Float
        ): RectFBox {
            val invScale = if (scale > 1e-6f) 1f / scale else 1f
            val localX1 = ((modelLeft - padLeft) * invScale).coerceIn(0f, captureWidth.toFloat())
            val localY1 = ((modelTop - padTop) * invScale).coerceIn(0f, captureHeight.toFloat())
            val localX2 = ((modelRight - padLeft) * invScale).coerceIn(0f, captureWidth.toFloat())
            val localY2 = ((modelBottom - padTop) * invScale).coerceIn(0f, captureHeight.toFloat())

            val x1 = (localX1 + cropOffsetX).coerceIn(0f, fullCaptureWidth.toFloat())
            val y1 = (localY1 + cropOffsetY).coerceIn(0f, fullCaptureHeight.toFloat())
            val x2 = (localX2 + cropOffsetX).coerceIn(0f, fullCaptureWidth.toFloat())
            val y2 = (localY2 + cropOffsetY).coerceIn(0f, fullCaptureHeight.toFloat())
            return RectFBox(
                left = min(x1, x2),
                top = min(y1, y2),
                right = max(x1, x2),
                bottom = max(y1, y2)
            )
        }

        fun capturePointToModelPoint(captureX: Float, captureY: Float): Pair<Float, Float> {
            return Pair(
                (captureX - cropOffsetX) * scale + padLeft,
                (captureY - cropOffsetY) * scale + padTop
            )
        }
    }

    fun computeLetterbox(
        captureWidth: Int,
        captureHeight: Int,
        modelSize: Int = MODEL_INPUT_SIZE,
        cropOffsetX: Int = 0,
        cropOffsetY: Int = 0,
        fullCaptureWidth: Int = captureWidth,
        fullCaptureHeight: Int = captureHeight
    ): LetterboxTransform {
        require(captureWidth > 0 && captureHeight > 0 && modelSize > 0) {
            "Invalid dimensions for letterbox: capture=${captureWidth}x${captureHeight}, model=$modelSize"
        }
        val scale = min(
            modelSize.toFloat() / captureWidth.toFloat(),
            modelSize.toFloat() / captureHeight.toFloat()
        )
        val resizedW = (captureWidth * scale).roundToInt().coerceIn(1, modelSize)
        val resizedH = (captureHeight * scale).roundToInt().coerceIn(1, modelSize)
        val padLeft = (modelSize - resizedW) * 0.5f
        val padTop = (modelSize - resizedH) * 0.5f
        return LetterboxTransform(
            captureWidth = captureWidth,
            captureHeight = captureHeight,
            modelSize = modelSize,
            scale = scale,
            resizedWidth = resizedW,
            resizedHeight = resizedH,
            padLeft = padLeft,
            padTop = padTop,
            cropOffsetX = cropOffsetX,
            cropOffsetY = cropOffsetY,
            fullCaptureWidth = fullCaptureWidth,
            fullCaptureHeight = fullCaptureHeight
        )
    }

    /**
     * Describes the relationship between the VirtualDisplay capture buffer and the real physical
     * screen + overlay window coordinate space.
     *
     * Does NOT assume capture resolution equals physical screen resolution.
     * Also accounts for potential letterboxing inside the VirtualDisplay if capture aspect ratio
     * differs slightly from physical display aspect ratio, and accounts for overlay window offset
     * (e.g., status bar / cutout offset reported by `View.getLocationOnScreen`).
     */
    data class CaptureToScreenTransform(
        val captureWidth: Int,
        val captureHeight: Int,
        val screenWidthPx: Int,
        val screenHeightPx: Int,
        val overlayWindowOffsetX: Int = 0,
        val overlayWindowOffsetY: Int = 0,
        val screenRotation: Int = 0
    ) {
        private val contentScale: Float
        private val contentPadX: Float
        private val contentPadY: Float

        init {
            require(captureWidth > 0 && captureHeight > 0 && screenWidthPx > 0 && screenHeightPx > 0) {
                "Invalid capture/screen dimensions: capture=${captureWidth}x${captureHeight}, screen=${screenWidthPx}x${screenHeightPx}"
            }
            // VirtualDisplay scales the physical screen into captureWidth x captureHeight
            // preserving aspect ratio and centering within the surface.
            contentScale = min(
                captureWidth.toFloat() / screenWidthPx.toFloat(),
                captureHeight.toFloat() / screenHeightPx.toFloat()
            )
            val drawnW = screenWidthPx * contentScale
            val drawnH = screenHeightPx * contentScale
            contentPadX = (captureWidth - drawnW) * 0.5f
            contentPadY = (captureHeight - drawnH) * 0.5f
        }

        fun capturePointToScreenPx(captureX: Float, captureY: Float): Pair<Float, Float> {
            val sx = ((captureX - contentPadX) / contentScale).coerceIn(0f, screenWidthPx.toFloat())
            val sy = ((captureY - contentPadY) / contentScale).coerceIn(0f, screenHeightPx.toFloat())
            return Pair(sx, sy)
        }

        fun capturePointToOverlayPx(captureX: Float, captureY: Float): Pair<Float, Float> {
            val (sx, sy) = capturePointToScreenPx(captureX, captureY)
            return Pair(sx - overlayWindowOffsetX, sy - overlayWindowOffsetY)
        }

        fun captureRectToOverlayRect(captureRect: RectFBox): RectFBox {
            val (l, t) = capturePointToOverlayPx(captureRect.left, captureRect.top)
            val (r, b) = capturePointToOverlayPx(captureRect.right, captureRect.bottom)
            return RectFBox(min(l, r), min(t, b), max(l, r), max(t, b))
        }

        fun screenRectToCaptureRect(
            screenLeft: Float,
            screenTop: Float,
            screenRight: Float,
            screenBottom: Float
        ): RectFBox {
            val l = (screenLeft * contentScale + contentPadX).coerceIn(0f, captureWidth.toFloat())
            val t = (screenTop * contentScale + contentPadY).coerceIn(0f, captureHeight.toFloat())
            val r = (screenRight * contentScale + contentPadX).coerceIn(0f, captureWidth.toFloat())
            val b = (screenBottom * contentScale + contentPadY).coerceIn(0f, captureHeight.toFloat())
            return RectFBox(min(l, r), min(t, b), max(l, r), max(t, b))
        }

        fun manualCalibrationToCaptureRect(calibration: ManualCalibrationNormalizedRect): RectFBox? {
            if (!calibration.isCompatibleWith(screenWidthPx, screenHeightPx, screenRotation)) {
                return null
            }
            return screenRectToCaptureRect(
                screenLeft = calibration.leftRatio * screenWidthPx,
                screenTop = calibration.topRatio * screenHeightPx,
                screenRight = calibration.rightRatio * screenWidthPx,
                screenBottom = calibration.bottomRatio * screenHeightPx
            )
        }
    }

    /**
     * Fast, deterministic 8x8 checkerboard pattern detector on an ARGB pixel buffer.
     *
     * Particularly useful on mobile portrait chess apps (such as Chess.com or Lichess) where the
     * 8x8 board spans the full screen width (`x = 0..width`) with zero left/right margin.
     * Scans candidate square regions for an 8x8 alternating light/dark square pattern and refines
     * the top/bottom/left/right boundaries using grid-line edge gradients.
     */
    fun detectCheckerboardRectFromPixels(
        argbPixels: IntArray,
        width: Int,
        height: Int
    ): RectFBox? {
        if (width < 120 || height < 120 || argbPixels.size < width * height) return null

        // Precompute fast integer luminance (0..255)
        val lum = IntArray(width * height)
        for (i in 0 until width * height) {
            val c = argbPixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            lum[i] = (r * 77 + g * 150 + b * 29) shr 8
        }

        val isPortrait = height >= width
        val minDim = min(width, height)

        // Candidate horizontal margins (0% = full-bleed like Chess.com, up to 8% inset)
        val candidateMarginRatios = if (isPortrait) {
            floatArrayOf(0.0f, 0.015f, 0.03f, 0.05f, 0.08f)
        } else {
            floatArrayOf(0.0f, 0.04f, 0.08f, 0.12f)
        }

        var bestRect: RectFBox? = null
        var bestScore = 0f

        for (marginRatio in candidateMarginRatios) {
            val side = (minDim * (1f - 2f * marginRatio)).roundToInt()
            if (side < 112) continue
            val cell = side / 8f

            val xCandidates = if (isPortrait) {
                intArrayOf(((width - side) * 0.5f).roundToInt())
            } else {
                val stepX = max(4, side / 16)
                val list = ArrayList<Int>()
                var x = 0
                while (x <= width - side) {
                    list.add(x)
                    x += stepX
                }
                list.toIntArray()
            }

            val stepY = max(3, (cell * 0.12f).roundToInt())
            for (left in xCandidates) {
                val right = left + side
                var top = 0
                while (top <= height - side) {
                    val bottom = top + side
                    val score = evaluateCheckerboardCandidate(lum, width, height, left, top, side)
                    if (score > bestScore) {
                        bestScore = score
                        bestRect = RectFBox(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
                    }
                    top += stepY
                }
            }
        }

        val coarse = bestRect ?: return null
        if (bestScore < 22f) return null

        // Fine 1px-step refinement around the best vertical position
        val coarseSide = coarse.width.roundToInt()
        val coarseLeft = coarse.left.roundToInt()
        val coarseTop = coarse.top.roundToInt()
        val refineRange = max(6, (coarseSide / 8f * 0.18f).roundToInt())

        var refinedTop = coarseTop
        var refinedBestScore = bestScore
        for (dy in -refineRange..refineRange) {
            val candTop = coarseTop + dy
            if (candTop < 0 || candTop + coarseSide > height) continue
            val sc = evaluateCheckerboardCandidate(lum, width, height, coarseLeft, candTop, coarseSide)
            if (sc > refinedBestScore) {
                refinedBestScore = sc
                refinedTop = candTop
            }
        }

        return RectFBox(
            left = coarseLeft.toFloat(),
            top = refinedTop.toFloat(),
            right = (coarseLeft + coarseSide).toFloat(),
            bottom = (refinedTop + coarseSide).toFloat()
        )
    }

    private fun evaluateCheckerboardCandidate(
        lum: IntArray,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        side: Int
    ): Float {
        val cell = side / 8f
        // Sample background in upper-right corner (u=0.80, v=0.20) and lower-left corner (u=0.22, v=0.78)
        // of each square (r, c), where chess pieces and rank/file labels rarely occlude the square color.
        val sqLum = FloatArray(64)
        for (r in 0..7) {
            val y1 = (top + (r + 0.20f) * cell).toInt().coerceIn(0, height - 1)
            val y2 = (top + (r + 0.78f) * cell).toInt().coerceIn(0, height - 1)
            for (c in 0..7) {
                val x1 = (left + (c + 0.80f) * cell).toInt().coerceIn(0, width - 1)
                val x2 = (left + (c + 0.22f) * cell).toInt().coerceIn(0, width - 1)
                val l1 = lum[y1 * width + x1]
                val l2 = lum[y2 * width + x2]
                sqLum[r * 8 + c] = max(l1, l2).toFloat()
            }
        }

        // Every single row r=0..7 and column c=0..7 must show alternating light-dark-light-dark contrast
        var totalRowContrast = 0f
        var minRowContrast = Float.MAX_VALUE
        for (r in 0..7) {
            var rowSum = 0f
            for (c in 0..6) {
                val sign = if ((r + c) % 2 == 0) 1f else -1f
                val diff = (sqLum[r * 8 + c] - sqLum[r * 8 + c + 1]) * sign
                rowSum += diff
            }
            val rowAvg = rowSum / 7f
            totalRowContrast += rowAvg
            if (rowAvg < minRowContrast) minRowContrast = rowAvg
        }

        // Standard chessboard has top-left (0,0) light -> positive contrast. Allow slight tolerance.
        val polarity = if (totalRowContrast >= 0f) 1f else -1f
        if (polarity < 0f) return 0f
        if (minRowContrast < 14f) return 0f

        var totalColContrast = 0f
        var minColContrast = Float.MAX_VALUE
        for (c in 0..7) {
            var colSum = 0f
            for (r in 0..6) {
                val sign = if ((r + c) % 2 == 0) 1f else -1f
                val diff = (sqLum[r * 8 + c] - sqLum[(r + 1) * 8 + c]) * sign
                colSum += diff
            }
            val colAvg = colSum / 7f
            totalColContrast += colAvg
            if (colAvg < minColContrast) minColContrast = colAvg
        }
        if (minColContrast < 14f) return 0f

        // Measure sharp grid line transitions at the 7 internal horizontal & vertical lines
        // plus the top (r=0) and bottom (r=8) outer board boundaries
        var hGridEdgeSum = 0f
        val d = max(2, (cell * 0.04f).toInt())
        for (k in 1..7) {
            val gy = (top + k * cell).roundToInt()
            val yAbove = (gy - d).coerceIn(0, height - 1)
            val yBelow = (gy + d).coerceIn(0, height - 1)
            for (c in 0..7) {
                val sx = (left + (c + 0.80f) * cell).toInt().coerceIn(0, width - 1)
                hGridEdgeSum += abs(lum[yAbove * width + sx] - lum[yBelow * width + sx])
            }
        }
        val hGridScore = hGridEdgeSum / (7f * 8f)

        // Outer top and bottom boundary contrast
        var outerBoundarySum = 0f
        val topAboveY = (top - d - 1).coerceIn(0, height - 1)
        val topInsideY = (top + d + 1).coerceIn(0, height - 1)
        val botInsideY = (top + side - d - 1).coerceIn(0, height - 1)
        val botBelowY = (top + side + d + 1).coerceIn(0, height - 1)
        for (c in 0..7) {
            val sx = (left + (c + 0.5f) * cell).toInt().coerceIn(0, width - 1)
            outerBoundarySum += abs(lum[topAboveY * width + sx] - lum[topInsideY * width + sx])
            outerBoundarySum += abs(lum[botInsideY * width + sx] - lum[botBelowY * width + sx])
        }
        val outerBoundaryScore = outerBoundarySum / 16f

        return (totalRowContrast / 8f) * 0.35f +
            (totalColContrast / 8f) * 0.35f +
            min(minRowContrast, minColContrast) * 0.25f +
            hGridScore * 0.20f +
            outerBoundaryScore * 0.15f
    }

    /**
     * Represents a square on the 8x8 chessboard.
     * - [gridRow]: 0..7 from top to bottom in image/screen space
     * - [gridCol]: 0..7 from left to right in image/screen space
     * - [rank]: 0..7 corresponding to rank '1'..'8'
     * - [file]: 0..7 corresponding to file 'a'..'h'
     */
    data class BoardSquare(
        val file: Int,
        val rank: Int
    ) {
        init {
            require(file in 0..7 && rank in 0..7) { "Invalid square file=$file rank=$rank" }
        }

        val algebraic: String
            get() = "${('a' + file)}${('1' + rank)}"

        companion object {
            fun fromAlgebraic(sq: String): BoardSquare? {
                if (sq.length < 2) return null
                val f = sq[0].lowercaseChar() - 'a'
                val r = sq[1] - '1'
                if (f !in 0..7 || r !in 0..7) return null
                return BoardSquare(f, r)
            }

            fun fromGrid(
                gridRow: Int,
                gridCol: Int,
                orientation: BoardOrientation
            ): BoardSquare {
                require(gridRow in 0..7 && gridCol in 0..7) {
                    "Grid indices out of range: row=$gridRow, col=$gridCol"
                }
                return when (orientation) {
                    BoardOrientation.WHITE_BOTTOM -> BoardSquare(
                        file = gridCol,
                        rank = 7 - gridRow
                    )
                    BoardOrientation.BLACK_BOTTOM -> BoardSquare(
                        file = 7 - gridCol,
                        rank = gridRow
                    )
                }
            }
        }
    }

    fun squareToGrid(
        square: BoardSquare,
        orientation: BoardOrientation
    ): Pair<Int, Int> {
        return when (orientation) {
            BoardOrientation.WHITE_BOTTOM -> Pair(
                7 - square.rank, // gridRow
                square.file      // gridCol
            )
            BoardOrientation.BLACK_BOTTOM -> Pair(
                square.rank,     // gridRow
                7 - square.file  // gridCol
            )
        }
    }

    /**
     * Maps a point `(px, py)` in capture frame coordinates into `(gridRow, gridCol)` in `0..7`
     * within [boardCaptureRect], or returns `null` if the point lies outside the board (with a
     * small edge tolerance of 8% of a single square for pieces slightly overhanging the edge).
     */
    fun mapCapturePointToGridCell(
        px: Float,
        py: Float,
        boardCaptureRect: RectFBox,
        edgeToleranceCells: Float = 0.08f
    ): Pair<Int, Int>? {
        if (!boardCaptureRect.isValid(32f)) return null
        val cellW = boardCaptureRect.width / 8f
        val cellH = boardCaptureRect.height / 8f
        val relX = (px - boardCaptureRect.left) / cellW
        val relY = (py - boardCaptureRect.top) / cellH

        if (relX < -edgeToleranceCells || relX > 8f + edgeToleranceCells ||
            relY < -edgeToleranceCells || relY > 8f + edgeToleranceCells
        ) {
            return null
        }
        val col = floor(relX.coerceIn(0f, 7.999f)).toInt().coerceIn(0, 7)
        val row = floor(relY.coerceIn(0f, 7.999f)).toInt().coerceIn(0, 7)
        return Pair(row, col)
    }

    /**
     * Computes the exact center of `(gridRow, gridCol)` in capture frame coordinates.
     */
    fun gridCellCenterInCapture(
        gridRow: Int,
        gridCol: Int,
        boardCaptureRect: RectFBox
    ): Pair<Float, Float> {
        val cellW = boardCaptureRect.width / 8f
        val cellH = boardCaptureRect.height / 8f
        val cx = boardCaptureRect.left + (gridCol + 0.5f) * cellW
        val cy = boardCaptureRect.top + (gridRow + 0.5f) * cellH
        return Pair(cx, cy)
    }

    /**
     * Computes the overlay arrow coordinates for a UCI move (e.g. `e2e4` or `e7e8q`)
     * from [boardCaptureRect], [orientation], and [captureToScreenTransform].
     */
    fun computeArrowOverlayCoordinates(
        bestMoveUci: String,
        promotionLabel: String?,
        boardCaptureRect: RectFBox,
        orientation: BoardOrientation,
        captureToScreen: CaptureToScreenTransform
    ): OverlayArrowCoordinates? {
        if (bestMoveUci.length < 4) return null
        val fromSq = BoardSquare.fromAlgebraic(bestMoveUci.substring(0, 2)) ?: return null
        val toSq = BoardSquare.fromAlgebraic(bestMoveUci.substring(2, 4)) ?: return null

        val (fromRow, fromCol) = squareToGrid(fromSq, orientation)
        val (toRow, toCol) = squareToGrid(toSq, orientation)

        val (fromCapX, fromCapY) = gridCellCenterInCapture(fromRow, fromCol, boardCaptureRect)
        val (toCapX, toCapY) = gridCellCenterInCapture(toRow, toCol, boardCaptureRect)

        val (fromOvX, fromOvY) = captureToScreen.capturePointToOverlayPx(fromCapX, fromCapY)
        val (toOvX, toOvY) = captureToScreen.capturePointToOverlayPx(toCapX, toCapY)
        val (boardLeftOv, boardTopOv) = captureToScreen.capturePointToOverlayPx(
            boardCaptureRect.left,
            boardCaptureRect.top
        )
        val (boardRightOv, boardBottomOv) = captureToScreen.capturePointToOverlayPx(
            boardCaptureRect.right,
            boardCaptureRect.bottom
        )

        return OverlayArrowCoordinates(
            fromX = fromOvX,
            fromY = fromOvY,
            toX = toOvX,
            toY = toOvY,
            boardLeftPx = boardLeftOv,
            boardTopPx = boardTopOv,
            boardRightPx = boardRightOv,
            boardBottomPx = boardBottomOv,
            fromSquare = fromSq.algebraic,
            toSquare = toSq.algebraic,
            promotionText = promotionLabel
        )
    }

    sealed class OrientationInferenceResult {
        data class Determined(
            val orientation: BoardOrientation,
            val reason: String
        ) : OrientationInferenceResult()

        data class Ambiguous(
            val reason: String
        ) : OrientationInferenceResult()
    }

    /**
     * Infers board orientation purely from the spatial distribution of detected pieces on the 8x8
     * image grid (top row = 0, bottom row = 7).
     *
     * CRITICAL RULE: Never uses "user's chosen piece color" to assert board orientation.
     * If the visual arrangement of pieces does not clearly distinguish White-at-bottom from
     * Black-at-bottom (e.g., endgame with kings/pieces near the middle ranks), returns [OrientationInferenceResult.Ambiguous]
     * so the user can explicitly select `白方在下` or `黑方在下`.
     */
    fun inferBoardOrientationFromGrid(
        gridPieces: Array<Array<ChessPiece?>>
    ): OrientationInferenceResult {
        var whiteWeightedRowSum = 0.0
        var whiteWeightTotal = 0.0
        var blackWeightedRowSum = 0.0
        var blackWeightTotal = 0.0

        for (r in 0..7) {
            for (c in 0..7) {
                val p = gridPieces[r][c] ?: continue
                // Kings, rooks, and pawns carry strong home-half / back-rank signal in opening/midgame
                val weight = when (p.type) {
                    PieceType.KING -> 2.0
                    PieceType.PAWN -> 1.5
                    PieceType.ROOK -> 1.2
                    else -> 1.0
                }
                if (p.color == PieceColor.WHITE) {
                    whiteWeightedRowSum += r * weight
                    whiteWeightTotal += weight
                } else {
                    blackWeightedRowSum += r * weight
                    blackWeightTotal += weight
                }
            }
        }

        if (whiteWeightTotal < 1.0 || blackWeightTotal < 1.0) {
            return OrientationInferenceResult.Ambiguous("棋盘上双方棋子数量不足，无法自动断言方向")
        }

        val whiteAvgRow = whiteWeightedRowSum / whiteWeightTotal
        val blackAvgRow = blackWeightedRowSum / blackWeightTotal
        val diff = whiteAvgRow - blackAvgRow

        return when {
            diff >= 1.45 -> OrientationInferenceResult.Determined(
                orientation = BoardOrientation.WHITE_BOTTOM,
                reason = "自动识别：白棋平均行=${"%.1f".format(whiteAvgRow)} (靠下)，黑棋平均行=${"%.1f".format(blackAvgRow)} (靠上)"
            )
            diff <= -1.45 -> OrientationInferenceResult.Determined(
                orientation = BoardOrientation.BLACK_BOTTOM,
                reason = "自动识别：黑棋平均行=${"%.1f".format(blackAvgRow)} (靠下)，白棋平均行=${"%.1f".format(whiteAvgRow)} (靠上)"
            )
            else -> OrientationInferenceResult.Ambiguous(
                reason = "残局或中局棋子南北分布接近 (白=${"%.1f".format(whiteAvgRow)}, 黑=${"%.1f".format(blackAvgRow)})，自动方向不确定，请明确选择「白方在下」或「黑方在下」"
            )
        }
    }
}
