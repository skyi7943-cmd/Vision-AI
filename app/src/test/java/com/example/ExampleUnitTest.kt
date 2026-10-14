package com.example

import com.example.capture.ImageFrameConverter
import com.example.chess.ChessBoardState
import com.example.chess.ChessRulesAndValidator
import com.example.chess.FullChessPosition
import com.example.chess.GameStateTracker
import com.example.chess.PositionValidationResult
import com.example.engine.EngineAnalysisOutcome
import com.example.engine.StockfishEngineController
import com.example.model.BoardOrientation
import com.example.model.CastlingRights
import com.example.model.ChessPiece
import com.example.model.ManualCalibrationNormalizedRect
import com.example.model.MoveSpecialType
import com.example.model.PerformanceMode
import com.example.model.PieceColor
import com.example.model.PieceType
import com.example.vision.CoordinateTransformer
import com.example.vision.ModelCompatibilityException
import com.example.vision.TfliteModelInspector
import com.example.vision.YoloChessDetector
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs

class ExampleUnitTest {

    // =========================================================================
    // 1. Two Board Orientations & Coordinate Transformations
    // =========================================================================

    @Test
    fun `coordinate mapping in WHITE_BOTTOM and BLACK_BOTTOM orientations is exact`() {
        // WHITE_BOTTOM: top-left (0,0) = a8, bottom-left (7,0) = a1, bottom-right (7,7) = h1
        val a8White = CoordinateTransformer.BoardSquare.fromGrid(0, 0, BoardOrientation.WHITE_BOTTOM)
        val a1White = CoordinateTransformer.BoardSquare.fromGrid(7, 0, BoardOrientation.WHITE_BOTTOM)
        val e2White = CoordinateTransformer.BoardSquare.fromGrid(6, 4, BoardOrientation.WHITE_BOTTOM)
        val e4White = CoordinateTransformer.BoardSquare.fromGrid(4, 4, BoardOrientation.WHITE_BOTTOM)
        assertEquals("a8", a8White.algebraic)
        assertEquals("a1", a1White.algebraic)
        assertEquals("e2", e2White.algebraic)
        assertEquals("e4", e4White.algebraic)

        // Inverse mapping for WHITE_BOTTOM
        assertEquals(Pair(6, 4), CoordinateTransformer.squareToGrid(e2White, BoardOrientation.WHITE_BOTTOM))
        assertEquals(Pair(4, 4), CoordinateTransformer.squareToGrid(e4White, BoardOrientation.WHITE_BOTTOM))

        // BLACK_BOTTOM: top-left (0,0) = h1, bottom-left (7,0) = h8, bottom-right (7,7) = a8
        val h1Black = CoordinateTransformer.BoardSquare.fromGrid(0, 0, BoardOrientation.BLACK_BOTTOM)
        val h8Black = CoordinateTransformer.BoardSquare.fromGrid(7, 0, BoardOrientation.BLACK_BOTTOM)
        val a8Black = CoordinateTransformer.BoardSquare.fromGrid(7, 7, BoardOrientation.BLACK_BOTTOM)
        val e2Black = CoordinateTransformer.BoardSquare.fromGrid(1, 3, BoardOrientation.BLACK_BOTTOM)
        assertEquals("h1", h1Black.algebraic)
        assertEquals("h8", h8Black.algebraic)
        assertEquals("a8", a8Black.algebraic)
        assertEquals("e2", e2Black.algebraic)

        // Inverse mapping for BLACK_BOTTOM
        assertEquals(Pair(1, 3), CoordinateTransformer.squareToGrid(e2Black, BoardOrientation.BLACK_BOTTOM))
    }

    @Test
    fun `letterbox scaling padding and overlay arrow coordinate chain is accurate`() {
        // Phone screen 1080x2400 captured at 720p mode -> 324x720 capture frame
        val perfConfig = PerformanceMode.LOW.resolveForDevice(
            availableProcessors = 8,
            sourceScreenWidth = 1080,
            sourceScreenHeight = 2400
        )
        assertEquals(324, perfConfig.captureWidth)
        assertEquals(720, perfConfig.captureHeight)
        assertEquals(2, perfConfig.visionCpuThreads)
        assertEquals(1, perfConfig.engineThreads)

        val letterbox = CoordinateTransformer.computeLetterbox(324, 720, 640)
        assertEquals(640f / 720f, letterbox.scale, 1e-5f)
        assertEquals(288, letterbox.resizedWidth)
        assertEquals(640, letterbox.resizedHeight)
        assertEquals(176f, letterbox.padLeft, 1e-4f)
        assertEquals(0f, letterbox.padTop, 1e-4f)

        // Forward and inverse point check
        val (mx, my) = letterbox.capturePointToModelPoint(162f, 360f)
        val recoveredBox = letterbox.modelBoxToCaptureBox(mx - 10f, my - 10f, mx + 10f, my + 10f)
        assertEquals(162f, recoveredBox.centerX, 0.5f)
        assertEquals(360f, recoveredBox.centerY, 0.5f)

        // Overlay mapping with window offset (e.g. status bar offset Y = 80px)
        val capToScreen = CoordinateTransformer.CaptureToScreenTransform(
            captureWidth = 324,
            captureHeight = 720,
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            overlayWindowOffsetX = 0,
            overlayWindowOffsetY = 80,
            screenRotation = 0
        )
        val boardCapRect = CoordinateTransformer.RectFBox(12f, 180f, 312f, 480f) // 300x300 board in capture
        val arrowWhite = CoordinateTransformer.computeArrowOverlayCoordinates(
            bestMoveUci = "e2e4",
            promotionLabel = null,
            boardCaptureRect = boardCapRect,
            orientation = BoardOrientation.WHITE_BOTTOM,
            captureToScreen = capToScreen
        )
        assertNotNull(arrowWhite)
        // In WHITE_BOTTOM, e2 is col 4, row 6; e4 is col 4, row 4 -> same X, e4 has smaller Y (higher on screen)
        assertEquals(arrowWhite!!.fromX, arrowWhite.toX, 0.01f)
        assertTrue(arrowWhite.toY < arrowWhite.fromY)

        // In BLACK_BOTTOM, e2 is col 3, row 1; e4 is col 3, row 3 -> e4 has larger Y (lower on screen)
        val arrowBlack = CoordinateTransformer.computeArrowOverlayCoordinates(
            bestMoveUci = "e2e4",
            promotionLabel = null,
            boardCaptureRect = boardCapRect,
            orientation = BoardOrientation.BLACK_BOTTOM,
            captureToScreen = capToScreen
        )
        assertNotNull(arrowBlack)
        assertEquals(arrowBlack!!.fromX, arrowBlack.toX, 0.01f)
        assertTrue(arrowBlack.toY > arrowBlack.fromY)
    }

    @Test
    fun `manual calibration validates screen rotation and aspect ratio compatibility`() {
        val cal = ManualCalibrationNormalizedRect(
            leftRatio = 0.05f,
            topRatio = 0.25f,
            rightRatio = 0.95f,
            bottomRatio = 0.65f,
            calibratedScreenWidth = 1080,
            calibratedScreenHeight = 2400,
            calibratedRotation = 0
        )
        assertTrue(cal.isCompatibleWith(1080, 2400, 0))
        assertTrue(cal.isCompatibleWith(1440, 3200, 0)) // Same aspect ratio & rotation
        assertFalse(cal.isCompatibleWith(2400, 1080, 1)) // Landscape rotation invalidates calibration
    }

    @Test
    fun `rgbaBufferToArgbIntArray handles non-zero rowPadding accurately`() {
        val width = 4
        val height = 3
        val pixelStride = 4
        val rowPadding = 7 // Non-multiple of pixelStride!
        val rowStride = width * pixelStride + rowPadding
        val buf = ByteBuffer.allocateDirect(height * rowStride)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pos = y * rowStride + x * pixelStride
                buf.put(pos, (x * 40).toByte())       // R
                buf.put(pos + 1, (y * 60).toByte())   // G
                buf.put(pos + 2, 128.toByte())        // B
                buf.put(pos + 3, 255.toByte())        // A
            }
        }
        val argb = ImageFrameConverter.rgbaBufferToArgbIntArray(buf, width, height, pixelStride, rowStride)
        assertEquals(width * height, argb.size)
        val p21 = argb[1 * width + 2] // x=2, y=1 -> R=80, G=60, B=128, A=255
        assertEquals(80, (p21 shr 16) and 0xFF)
        assertEquals(60, (p21 shr 8) and 0xFF)
        assertEquals(128, p21 and 0xFF)
    }

    // =========================================================================
    // 2. Real TFLite Model Inspection, Rejection of Invalid Models & Real Sample Inference
    // =========================================================================

    @Test
    fun `real best_f16 tflite model passes FlatBuffer inspection and SHA256 verification`() {
        val modelFile = File("src/main/assets/best_f16.tflite").let {
            if (it.exists()) it else File("app/src/main/assets/best_f16.tflite")
        }
        assertTrue("best_f16.tflite must exist in assets", modelFile.exists())

        RandomAccessFile(modelFile, "r").channel.use { ch ->
            val mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
            val metadata = TfliteModelInspector.inspectAndValidate(mapped)
            assertEquals(TfliteModelInspector.EXPECTED_SIZE_BYTES, metadata.sizeBytes)
            assertEquals(TfliteModelInspector.EXPECTED_SHA256, metadata.sha256Hex)
            assertEquals(listOf(1, 640, 640, 3), metadata.inputTensor.shape)
            assertEquals("FLOAT32", metadata.inputTensor.dataType)
            assertEquals(listOf(1, 17, 8400), metadata.outputTensor.shape)
            assertEquals("FLOAT32", metadata.outputTensor.dataType)
        }
    }

    @Test(expected = ModelCompatibilityException::class)
    fun `HTML content disguised as tflite is rejected with ModelCompatibilityException`() {
        val htmlBytes = "<!DOCTYPE html><html><body>Error 404 Not Found padding bytes to exceed 128 bytes........................................................................</body></html>"
            .toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.wrap(htmlBytes)
        TfliteModelInspector.inspectAndValidate(buf)
    }

    @Test(expected = ModelCompatibilityException::class)
    fun `Git LFS pointer disguised as tflite is rejected with ModelCompatibilityException`() {
        val lfsBytes = "version https://git-lfs.github.com/spec/v1\noid sha256:2bfabe22f91266a2308a7df29c2a22afbbdb537fb2040ef8c93f6ed4fc144033\nsize 51884986\n"
            .toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.wrap(lfsBytes)
        TfliteModelInspector.inspectAndValidate(buf)
    }

    // =========================================================================
    // 3. Same-Square Conflicts & Unreliable Recognition Handling
    // =========================================================================

    @Test
    fun `same square conflict between two different piece classes is flagged as unreliable`() {
        val rawOutput = Array(17) { FloatArray(8400) }
        val letterbox = CoordinateTransformer.computeLetterbox(640, 640, 640)

        // Anchor 0: board covering [80, 80, 560, 560]
        rawOutput[0][0] = 320f / 640f
        rawOutput[1][0] = 320f / 640f
        rawOutput[2][0] = 480f / 640f
        rawOutput[3][0] = 480f / 640f
        rawOutput[4 + ChessPiece.CLASS_ID_BOARD][0] = 0.96f

        // Anchor 1: white_queen at square (row=0, col=0) -> center (110, 110)
        rawOutput[0][1] = 110f / 640f
        rawOutput[1][1] = 110f / 640f
        rawOutput[2][1] = 48f / 640f
        rawOutput[3][1] = 48f / 640f
        rawOutput[4 + ChessPiece.WHITE_QUEEN.yoloClassId][1] = 0.82f

        // Anchor 2: black_rook at slightly shifted box in the SAME square (row=0, col=0) -> center (125, 118)
        rawOutput[0][2] = 125f / 640f
        rawOutput[1][2] = 118f / 640f
        rawOutput[2][2] = 48f / 640f
        rawOutput[3][2] = 48f / 640f
        rawOutput[4 + ChessPiece.BLACK_ROOK.yoloClassId][2] = 0.78f

        // Anchor 3: white_king at (row=7, col=4)
        rawOutput[0][3] = 350f / 640f
        rawOutput[1][3] = 530f / 640f
        rawOutput[2][3] = 48f / 640f
        rawOutput[3][3] = 48f / 640f
        rawOutput[4 + ChessPiece.WHITE_KING.yoloClassId][3] = 0.91f

        val result = YoloChessDetector.decodeAndPostprocess(rawOutput, letterbox)
        assertFalse("Conflicting square detections must mark recognition as unreliable", result.isReliable)
        assertEquals(1, result.conflicts.size)
        assertTrue(result.unreliabilityReason!!.contains("同格冲突"))
    }

    // =========================================================================
    // 4. FEN Reconstruction, Unknown History State & Position Legality Validation
    // =========================================================================

    @Test
    fun `legality validator rejects missing king, adjacent kings, first-rank pawn, and non-active side in check`() {
        // 1. Missing black king
        val missingKingPos = FullChessPosition.fromFen("8/8/8/8/8/8/4K3/4R3 w - - 0 1")!!
        val res1 = ChessRulesAndValidator.validatePosition(missingKingPos)
        assertTrue(res1 is PositionValidationResult.Invalid)

        // 2. Adjacent kings (e4 and e5)
        val adjacentKingsPos = FullChessPosition.fromFen("8/8/8/4k3/4K3/8/8/8 w - - 0 1")!!
        val res2 = ChessRulesAndValidator.validatePosition(adjacentKingsPos)
        assertTrue(res2 is PositionValidationResult.Invalid)
        assertTrue((res2 as PositionValidationResult.Invalid).reason.contains("相邻"))

        // 3. Pawn on 1st or 8th rank
        val illegalPawnPos = FullChessPosition.fromFen("4k2P/8/8/8/8/8/8/4K3 w - - 0 1")!!
        val res3 = ChessRulesAndValidator.validatePosition(illegalPawnPos)
        assertTrue(res3 is PositionValidationResult.Invalid)
        assertTrue((res3 as PositionValidationResult.Invalid).reason.contains("兵"))

        // 4. Non-active side is in check (White to move, but Black King on e8 is attacked by White Rook on e1)
        val nonActiveInCheckPos = FullChessPosition.fromFen("4k3/8/8/8/8/8/3K4/4R3 w - - 0 1")!!
        val res4 = ChessRulesAndValidator.validatePosition(nonActiveInCheckPos)
        assertTrue(res4 is PositionValidationResult.Invalid)
        assertTrue((res4 as PositionValidationResult.Invalid).reason.contains("被将军"))
    }

    @Test
    fun `GameStateTracker tracks legal moves from initial position and requires confirmation on midgame join`() {
        val tracker = GameStateTracker()

        // Step 1: Initial position auto-syncs without needing confirmation
        val startBoard = ChessBoardState.fromPiecePlacementFen(ChessBoardState.INITIAL_PLACEMENT_FEN)!!
        val r1 = tracker.resolveRecognizedBoard(startBoard, PieceColor.WHITE)
        assertTrue(r1 is GameStateTracker.StateResolution.Ready)
        assertEquals(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            (r1 as GameStateTracker.StateResolution.Ready).position.toFen()
        )

        // Step 2: 2 plies later (1. e4 e5) -> automatically tracks castling, ep, and fullmove
        val afterE4E5 = ChessBoardState.fromPiecePlacementFen("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR")!!
        val r2 = tracker.resolveRecognizedBoard(afterE4E5, PieceColor.WHITE)
        assertTrue(r2 is GameStateTracker.StateResolution.Ready)
        val pos2 = (r2 as GameStateTracker.StateResolution.Ready).position
        assertEquals("e6", pos2.enPassantSquare)
        assertEquals(2, pos2.fullmoveNumber)

        // Step 3: Arbitrary jump to an unrelated board while tracking -> requires resync!
        val unrelatedBoard = ChessBoardState.fromPiecePlacementFen("6R1/k2Q4/pp6/7p/3p4/P1N1P3/1P2K1P1/2R5")!!
        val r3 = tracker.resolveRecognizedBoard(unrelatedBoard, PieceColor.WHITE)
        assertTrue("Unexplained jump must require resync", r3 is GameStateTracker.StateResolution.RequiresResync)

        // Step 4: Fresh tracker joining mid-game where King & Rook are on e1/h1 -> requires mid-game confirmation!
        val midgameTracker = GameStateTracker()
        val midgameWithKingAndRookOnHome = ChessBoardState.fromPiecePlacementFen("2k2r2/1pP2pp1/p1r1p2p/3p3P/3P1Pn1/P1N1P3/1P4P1/2R1K2R")!!
        val r4 = midgameTracker.resolveRecognizedBoard(midgameWithKingAndRookOnHome, PieceColor.WHITE)
        assertTrue(
            "Midgame with unconfirmed K on e1 and R on h1 must request confirmation, got $r4",
            r4 is GameStateTracker.StateResolution.RequiresMidgameConfirmation
        )

        // Confirming midgame state resolves cleanly with halfmove=0, fullmove=1
        midgameTracker.setUserConfirmedMidgameState(CastlingRights(whiteKingSide = true, false, false, false), null)
        val r5 = midgameTracker.resolveRecognizedBoard(midgameWithKingAndRookOnHome, PieceColor.WHITE)
        assertTrue(r5 is GameStateTracker.StateResolution.Ready)
        assertEquals(
            "2k2r2/1pP2pp1/p1r1p2p/3p3P/3P1Pn1/P1N1P3/1P4P1/2R1K2R w K - 0 1",
            (r5 as GameStateTracker.StateResolution.Ready).position.toFen()
        )
    }

    // =========================================================================
    // 5. Special Moves, Checkmate/Stalemate & Real Stockfish 18 UCI Engine Verification
    // =========================================================================

    @Test
    fun `special moves castling en-passant promotion checkmate and stalemate are classified accurately`() {
        // Castling (White O-O and O-O-O)
        val castlePos = FullChessPosition.fromFen("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1")!!
        val oo = ChessRulesAndValidator.findLegalMoveByUci(castlePos, "e1g1")
        val ooo = ChessRulesAndValidator.findLegalMoveByUci(castlePos, "e1c1")
        assertNotNull(oo)
        assertNotNull(ooo)
        assertEquals(MoveSpecialType.CASTLING_KINGSIDE, oo!!.specialType)
        assertEquals(MoveSpecialType.CASTLING_QUEENSIDE, ooo!!.specialType)

        // En Passant (White pawn on e5 captures Black pawn on d5 via e5d6)
        val epPos = FullChessPosition.fromFen("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1")!!
        val epMove = ChessRulesAndValidator.findLegalMoveByUci(epPos, "e5d6")
        assertNotNull(epMove)
        assertEquals(MoveSpecialType.EN_PASSANT, epMove!!.specialType)
        val afterEp = ChessRulesAndValidator.applyLegalMove(epPos, epMove)
        assertNull("Captured en-passant pawn on d5 must be removed", afterEp.board.get(3, 4))

        // Promotion & Capture-Promotion
        val promoPos = FullChessPosition.fromFen("3rk3/2P5/8/8/8/8/8/4K3 w - - 0 1")!!
        val promoQ = ChessRulesAndValidator.findLegalMoveByUci(promoPos, "c7c8q")
        val capPromoN = ChessRulesAndValidator.findLegalMoveByUci(promoPos, "c7d8n")
        assertNotNull(promoQ)
        assertNotNull(capPromoN)
        assertEquals(MoveSpecialType.PROMOTION, promoQ!!.specialType)
        assertEquals(PieceType.QUEEN, promoQ.promotionType)
        assertTrue(promoQ.promotionLabelChinese()!!.contains("后"))
        assertEquals(MoveSpecialType.CAPTURE_PROMOTION, capPromoN!!.specialType)
        assertEquals(PieceType.KNIGHT, capPromoN.promotionType)

        // Checkmate (Fool's mate)
        val checkmatePos = FullChessPosition.fromFen("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3")!!
        val validMate = ChessRulesAndValidator.validatePosition(checkmatePos) as PositionValidationResult.Valid
        assertTrue(validMate.isInCheck)
        assertTrue(validMate.legalMoves.isEmpty())

        // Stalemate
        val stalematePos = FullChessPosition.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")!!
        val validStale = ChessRulesAndValidator.validatePosition(stalematePos) as PositionValidationResult.Valid
        assertFalse(validStale.isInCheck)
        assertTrue(validStale.legalMoves.isEmpty())
    }

    @Test
    fun `real Stockfish 18 engine analyzes positions and discards superseded stale requests`() = runBlocking {
        val hostSf = File("/tmp/sf18/stockfish/stockfish-ubuntu-x86-64")
        if (!hostSf.exists()) return@runBlocking

        val engine = StockfishEngineController(
            context = null,
            customExecutablePathProvider = { hostSf }
        )
        try {
            val meta = engine.ensureStarted(threads = 2, hashMb = 32)
            assertTrue(meta.engineName.contains("Stockfish 18"))
            assertEquals("nn-c288c895ea92.nnue", meta.evalFileBig)
            assertEquals("nn-37f18f62d772.nnue", meta.evalFileSmall)

            // 1a. Verify terminal checkmate position from detection_example1.png (`6R1/k2Q4/pp6/7p/3p4/P1N1P3/1P2K1P1/2R5 b - - 0 1`)
            val matePos = FullChessPosition.fromFen("6R1/k2Q4/pp6/7p/3p4/P1N1P3/1P2K1P1/2R5 b - - 0 1")!!
            val mateOut = engine.analyzePosition(engine.nextRequestId(), matePos, threads = 2, hashMb = 32, searchTimeMs = 150L)
            assertTrue("Expected TerminalPosition(CHECKMATE) but got $mateOut", mateOut is EngineAnalysisOutcome.TerminalPosition)
            assertEquals(MoveSpecialType.CHECKMATE, (mateOut as EngineAnalysisOutcome.TerminalPosition).terminalType)

            // 1b. Analyze tactical position from detection_example2.png (`1k4r1/2p3P1/pp2p2p/8/3P1PnR/P1N1P3/1P2K1P1/2R5 w - - 0 1`)
            val pos1 = FullChessPosition.fromFen("1k4r1/2p3P1/pp2p2p/8/3P1PnR/P1N1P3/1P2K1P1/2R5 w - - 0 1")!!
            val req1 = engine.nextRequestId()
            val out1 = engine.analyzePosition(req1, pos1, threads = 2, hashMb = 32, searchTimeMs = 180L)
            assertTrue("Expected BestMoveFound but got $out1", out1 is EngineAnalysisOutcome.BestMoveFound)
            val best1 = (out1 as EngineAnalysisOutcome.BestMoveFound).bestMove
            assertNotNull(ChessRulesAndValidator.findLegalMoveByUci(pos1, best1.uci))

            // 2. Test concurrent/superseded request discarding:
            // Launch a slow 600ms request, then after 40ms launch a new request with a higher requestId.
            val slowReqId = engine.nextRequestId()
            val slowDeferred = async {
                engine.analyzePosition(slowReqId, pos1, threads = 1, hashMb = 32, searchTimeMs = 600L)
            }
            delay(50L)
            val newerReqId = engine.nextRequestId()
            val pos2 = FullChessPosition.fromFen("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")!!
            val newerOutcome = engine.analyzePosition(newerReqId, pos2, threads = 1, hashMb = 32, searchTimeMs = 150L)
            val slowOutcome = slowDeferred.await()

            assertTrue(
                "Superseded request must be discarded as stale, got $slowOutcome",
                slowOutcome is EngineAnalysisOutcome.DiscardedStale
            )
            assertTrue(
                "Newer request must succeed, got $newerOutcome",
                newerOutcome is EngineAnalysisOutcome.BestMoveFound
            )
        } finally {
            engine.stopEngine()
        }
    }

    @Test
    fun `detectCheckerboardRectFromPixels locates full-width Chess_com board on portrait screen`() {
        val capW = 480
        val capH = 1040
        val boardTop = 316
        val boardSide = 480
        val cell = boardSide / 8 // 60px per square

        val bgDarkUi = (0xFF shl 24) or (0x26 shl 16) or (0x24 shl 8) or 0x21
        val sqLight = (0xFF shl 24) or (0xEB shl 16) or (0xEC shl 8) or 0xD0 // Chess.com #EBECD0
        val sqDark = (0xFF shl 24) or (0x73 shl 16) or (0x95 shl 8) or 0x52  // Chess.com #739552
        val speechBubbleWhite = (0xFF shl 24) or (0xFF shl 16) or (0xFF shl 8) or 0xFF

        val pixels = IntArray(capW * capH) { bgDarkUi }

        // Draw speech bubble above the board (like "Hi, I'm Zach! Let's play!")
        for (y in 220 until 302) {
            for (x in 105 until 468) {
                pixels[y * capW + x] = speechBubbleWhite
            }
        }

        // Draw 8x8 Chess.com board from y = boardTop .. boardTop + boardSide
        for (r in 0..7) {
            for (c in 0..7) {
                val isLight = (r + c) % 2 == 0
                val sqColor = if (isLight) sqLight else sqDark
                val y0 = boardTop + r * cell
                val x0 = c * cell
                for (yy in y0 until (y0 + cell)) {
                    for (xx in x0 until (x0 + cell)) {
                        pixels[yy * capW + xx] = sqColor
                    }
                }
                // Simulate chess pieces in the center of ranks 8, 7, 2, 1
                if (r in listOf(0, 1, 6, 7)) {
                    val pieceColor = if (r <= 1) {
                        (0xFF shl 24) or (0x3D shl 16) or (0x3B shl 8) or 0x38
                    } else {
                        (0xFF shl 24) or (0xF8 shl 16) or (0xF8 shl 8) or 0xF6
                    }
                    for (yy in (y0 + 14) until (y0 + cell - 10)) {
                        for (xx in (x0 + 14) until (x0 + cell - 14)) {
                            pixels[yy * capW + xx] = pieceColor
                        }
                    }
                }
            }
        }

        val detected = CoordinateTransformer.detectCheckerboardRectFromPixels(pixels, capW, capH)
        assertNotNull("Checkerboard detector must locate the 8x8 board on portrait screen", detected)
        assertEquals(0f, detected!!.left, 4f)
        assertEquals(480f, detected.right, 4f)
        assertEquals(boardTop.toFloat(), detected.top, 6f)
        assertEquals((boardTop + boardSide).toFloat(), detected.bottom, 6f)
    }
}

