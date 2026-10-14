package com.example.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Three performance tiers sharing the same vision model (`best_f16.tflite`) and analysis pipeline.
 *
 * Initial parameters per specification:
 * | Mode | Max Capture Long Edge | Vision CPU Threads | Engine Threads | Hash  | Search Time |
 * |------|----------------------:|-------------------:|---------------:|------:|------------:|
 * | LOW  |                 720px |                  2 |              1 |  32MB |       250ms |
 * | MED  |                1080px |                  4 |              2 |  64MB |       500ms |
 * | HIGH |                1440px |                  4 |              4 | 128MB |       800ms |
 */
enum class PerformanceMode(
    val displayName: String,
    val maxCaptureLongEdgePx: Int,
    val initialVisionCpuThreads: Int,
    val initialEngineThreads: Int,
    val initialEngineHashMb: Int,
    val searchTimeMs: Long
) {
    LOW(
        displayName = "低性能 (720p / 250ms)",
        maxCaptureLongEdgePx = 720,
        initialVisionCpuThreads = 2,
        initialEngineThreads = 1,
        initialEngineHashMb = 32,
        searchTimeMs = 250L
    ),
    MEDIUM(
        displayName = "中性能 (1080p / 500ms)",
        maxCaptureLongEdgePx = 1080,
        initialVisionCpuThreads = 4,
        initialEngineThreads = 2,
        initialEngineHashMb = 64,
        searchTimeMs = 500L
    ),
    HIGH(
        displayName = "高性能 (1440p / 800ms)",
        maxCaptureLongEdgePx = 1440,
        initialVisionCpuThreads = 4,
        initialEngineThreads = 4,
        initialEngineHashMb = 128,
        searchTimeMs = 800L
    );

    data class EffectiveRuntimeConfig(
        val mode: PerformanceMode,
        val captureWidth: Int,
        val captureHeight: Int,
        val visionCpuThreads: Int,
        val engineThreads: Int,
        val engineHashMb: Int,
        val searchTimeMs: Long
    )

    /**
     * Clamps threads to actual CPU cores and ensures capture resolution never meaninglessly
     * exceeds the source physical screen resolution while preserving aspect ratio (even dimensions).
     */
    fun resolveForDevice(
        availableProcessors: Int,
        sourceScreenWidth: Int,
        sourceScreenHeight: Int,
        maxMemoryMb: Long = 512L
    ): EffectiveRuntimeConfig {
        val safeCores = availableProcessors.coerceAtLeast(1)
        val visionThreads = initialVisionCpuThreads.coerceIn(1, safeCores)
        val engineThreads = initialEngineThreads.coerceIn(1, safeCores)
        val safeHashMb = min(initialEngineHashMb.toLong(), (maxMemoryMb / 4L).coerceAtLeast(16L)).toInt()

        val srcW = sourceScreenWidth.coerceAtLeast(64)
        val srcH = sourceScreenHeight.coerceAtLeast(64)
        val srcLongEdge = max(srcW, srcH)
        val targetLongEdge = min(maxCaptureLongEdgePx, srcLongEdge)
        val scale = targetLongEdge.toDouble() / srcLongEdge.toDouble()

        // Ensure even dimensions for VirtualDisplay / ImageReader compatibility
        val capW = ((srcW * scale).toInt() / 2 * 2).coerceAtLeast(64)
        val capH = ((srcH * scale).toInt() / 2 * 2).coerceAtLeast(64)

        return EffectiveRuntimeConfig(
            mode = this,
            captureWidth = capW,
            captureHeight = capH,
            visionCpuThreads = visionThreads,
            engineThreads = engineThreads,
            engineHashMb = safeHashMb,
            searchTimeMs = searchTimeMs
        )
    }
}

enum class PieceColor(val displayName: String, val fenToken: String) {
    WHITE("白棋 (White)", "w"),
    BLACK("黑棋 (Black)", "b");

    fun opposite(): PieceColor = if (this == WHITE) BLACK else WHITE
}

enum class PieceType(val displayName: String, val algebraicSymbol: Char) {
    KING("王", 'K'),
    QUEEN("后", 'Q'),
    ROOK("车", 'R'),
    BISHOP("象", 'B'),
    KNIGHT("马", 'N'),
    PAWN("兵", 'P')
}

/**
 * Exact class mapping from `NAKSTStudio/yolov8m-chess-piece-detection` (`config.json` & `README.md`):
 * 0: board
 * 1: white_king
 * 2: white_queen
 * 3: white_rook
 * 4: white_bishop
 * 5: white_knight
 * 6: white_pawn
 * 7: black_king
 * 8: black_queen
 * 9: black_rook
 * 10: black_bishop
 * 11: black_knight
 * 12: black_pawn
 */
enum class ChessPiece(
    val yoloClassId: Int,
    val labelName: String,
    val color: PieceColor,
    val type: PieceType,
    val fenChar: Char
) {
    WHITE_KING(1, "white_king", PieceColor.WHITE, PieceType.KING, 'K'),
    WHITE_QUEEN(2, "white_queen", PieceColor.WHITE, PieceType.QUEEN, 'Q'),
    WHITE_ROOK(3, "white_rook", PieceColor.WHITE, PieceType.ROOK, 'R'),
    WHITE_BISHOP(4, "white_bishop", PieceColor.WHITE, PieceType.BISHOP, 'B'),
    WHITE_KNIGHT(5, "white_knight", PieceColor.WHITE, PieceType.KNIGHT, 'N'),
    WHITE_PAWN(6, "white_pawn", PieceColor.WHITE, PieceType.PAWN, 'P'),
    BLACK_KING(7, "black_king", PieceColor.BLACK, PieceType.KING, 'k'),
    BLACK_QUEEN(8, "black_queen", PieceColor.BLACK, PieceType.QUEEN, 'q'),
    BLACK_ROOK(9, "black_rook", PieceColor.BLACK, PieceType.ROOK, 'r'),
    BLACK_BISHOP(10, "black_bishop", PieceColor.BLACK, PieceType.BISHOP, 'b'),
    BLACK_KNIGHT(11, "black_knight", PieceColor.BLACK, PieceType.KNIGHT, 'n'),
    BLACK_PAWN(12, "black_pawn", PieceColor.BLACK, PieceType.PAWN, 'p');

    companion object {
        const val CLASS_ID_BOARD = 0
        const val NUM_MODEL_CLASSES = 13

        private val byClassId = entries.associateBy { it.yoloClassId }
        private val byFenChar = entries.associateBy { it.fenChar }

        fun fromYoloClassId(classId: Int): ChessPiece? = byClassId[classId]
        fun fromFenChar(ch: Char): ChessPiece? = byFenChar[ch]
    }
}

enum class BoardOrientationMode(val displayName: String) {
    AUTO("自动识别方向"),
    WHITE_BOTTOM("白方在下 (a1左下)"),
    BLACK_BOTTOM("黑方在下 (h8左下)")
}

enum class BoardOrientation(val displayName: String) {
    WHITE_BOTTOM("白方在下 (白棋向上走)"),
    BLACK_BOTTOM("黑方在下 (黑棋向上走)")
}

data class CastlingRights(
    val whiteKingSide: Boolean = true,
    val whiteQueenSide: Boolean = true,
    val blackKingSide: Boolean = true,
    val blackQueenSide: Boolean = true
) {
    fun toFen(): String {
        val sb = StringBuilder()
        if (whiteKingSide) sb.append('K')
        if (whiteQueenSide) sb.append('Q')
        if (blackKingSide) sb.append('k')
        if (blackQueenSide) sb.append('q')
        return if (sb.isEmpty()) "-" else sb.toString()
    }

    fun isAnyEnabled(): Boolean = whiteKingSide || whiteQueenSide || blackKingSide || blackQueenSide

    companion object {
        val NONE = CastlingRights(false, false, false, false)
        val ALL = CastlingRights(true, true, true, true)

        fun fromFen(fenToken: String): CastlingRights {
            if (fenToken == "-" || fenToken.isBlank()) return NONE
            return CastlingRights(
                whiteKingSide = 'K' in fenToken,
                whiteQueenSide = 'Q' in fenToken,
                blackKingSide = 'k' in fenToken,
                blackQueenSide = 'q' in fenToken
            )
        }
    }
}

/**
 * Normalized manual chessboard calibration region [0f..1f] along with the screen condition
 * when calibrated so we can validate whether it still applies if rotation/resolution changes.
 */
data class ManualCalibrationNormalizedRect(
    val leftRatio: Float,
    val topRatio: Float,
    val rightRatio: Float,
    val bottomRatio: Float,
    val calibratedScreenWidth: Int,
    val calibratedScreenHeight: Int,
    val calibratedRotation: Int
) {
    val widthRatio: Float get() = rightRatio - leftRatio
    val heightRatio: Float get() = bottomRatio - topRatio

    fun isValidGeometry(): Boolean {
        return leftRatio in 0f..0.9f &&
            topRatio in 0f..0.9f &&
            rightRatio in 0.1f..1f &&
            bottomRatio in 0.1f..1f &&
            widthRatio >= 0.2f &&
            heightRatio >= 0.15f &&
            calibratedScreenWidth > 0 &&
            calibratedScreenHeight > 0
    }

    fun isCompatibleWith(screenWidth: Int, screenHeight: Int, rotation: Int): Boolean {
        if (!isValidGeometry()) return false
        if (rotation != calibratedRotation) return false
        if (screenWidth <= 0 || screenHeight <= 0) return false
        val oldAspect = calibratedScreenWidth.toFloat() / calibratedScreenHeight.toFloat()
        val newAspect = screenWidth.toFloat() / screenHeight.toFloat()
        return abs(oldAspect - newAspect) <= 0.05f
    }
}

enum class OverlayButtonSymbol(val symbol: String, val description: String) {
    READY("♟", "就绪：点击识别并分析"),
    ANALYZING("…", "分析中：正在捕捉与推理"),
    SUCCESS("✓", "成功：已绘制推荐走法箭头"),
    FAILED("!", "失败：请查看原因或重试")
}

enum class MoveSpecialType(val label: String) {
    NORMAL("普通走法"),
    CAPTURE("吃子"),
    CASTLING_KINGSIDE("王翼易位 (O-O)"),
    CASTLING_QUEENSIDE("后翼易位 (O-O-O)"),
    EN_PASSANT("吃过路兵 (e.p.)"),
    PROMOTION("升变"),
    CAPTURE_PROMOTION("吃子并升变"),
    CHECKMATE("将死 (Checkmate)"),
    STALEMATE("逼和 (Stalemate)"),
    NO_LEGAL_MOVES("无合法走法")
}

data class OverlayArrowCoordinates(
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float,
    val boardLeftPx: Float,
    val boardTopPx: Float,
    val boardRightPx: Float,
    val boardBottomPx: Float,
    val fromSquare: String,
    val toSquare: String,
    val promotionText: String? = null
)

data class AnalysisReport(
    val requestId: Long,
    val timestampMs: Long,
    val success: Boolean,
    val performanceMode: PerformanceMode,
    val analyzedColor: PieceColor,
    val resolvedOrientation: BoardOrientation?,
    val orientationSource: String,
    val boardSource: String,
    val detectedPiecesCount: Int,
    val fen: String?,
    val fenStateProvenance: String,
    val bestMoveUci: String?,
    val moveDescription: String?,
    val moveSpecialType: MoveSpecialType?,
    val promotionPieceLabel: String?,
    val evaluationText: String?,
    val visionLatencyMs: Long,
    val engineLatencyMs: Long,
    val arrowOverlay: OverlayArrowCoordinates?,
    val requiresUserConfirmation: Boolean = false,
    val errorOrWarningMessage: String? = null
)

data class ComponentRuntimeStatus(
    val captureActive: Boolean = false,
    val captureStatusText: String = "未启动 (需授权屏幕捕捉)",
    val overlayActive: Boolean = false,
    val overlayPermissionGranted: Boolean = false,
    val overlayStatusText: String = "未启动",
    val modelLoaded: Boolean = false,
    val modelStatusText: String = "未加载 (best_f16.tflite)",
    val modelDetailsText: String = "NAKSTStudio/yolov8m-chess-piece-detection (AGPL-3.0)",
    val engineReady: Boolean = false,
    val engineStatusText: String = "未启动 (Stockfish 18)",
    val engineDetailsText: String = "Stockfish 18 ARM64 + NNUE (nn-c288c895ea92 / nn-37f18f62d772)",
    val overlayButtonSymbol: OverlayButtonSymbol = OverlayButtonSymbol.READY
)
