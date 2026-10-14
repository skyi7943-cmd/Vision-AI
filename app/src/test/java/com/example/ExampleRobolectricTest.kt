package com.example

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.capture.TrainerSessionOrchestrator
import com.example.engine.StockfishEngineController
import com.example.model.OverlayArrowCoordinates
import com.example.model.OverlayButtonSymbol
import com.example.model.PerformanceMode
import com.example.model.PieceColor
import com.example.vision.CoordinateTransformer
import com.example.vision.RawYoloInferenceBackend
import com.example.vision.VerifiedTfliteModelMetadata
import com.example.vision.YoloChessDetector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `MainActivity displays controls and switches performance and orientation modes`() {
        composeTestRule.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("ChessVisionTrainer", context.getString(R.string.app_name))

        composeTestRule.onNodeWithTag("header_banner_card").assertExists()
        composeTestRule.onNodeWithTag("one_click_start_button").assertExists()
        composeTestRule.onNodeWithTag("stop_all_button").assertExists()

        // Scroll to and switch performance mode to HIGH and verify
        composeTestRule.onNodeWithTag("perf_mode_high").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        val orchestrator = TrainerSessionOrchestrator.getInstance(context)
        assertEquals(PerformanceMode.HIGH, orchestrator.performanceMode.value)

        // Scroll to and switch player color to BLACK and verify
        composeTestRule.onNodeWithTag("user_color_black").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(PieceColor.BLACK, orchestrator.userColor.value)
    }

    @Test
    fun `end-to-end real sample image through YoloChessDetector and Stockfish 18 produces valid arrow`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sampleFile = File("src/test/resources/detection_example2.png").let {
            if (it.exists()) it else File("app/src/test/resources/detection_example2.png")
        }
        val modelFile = File("src/main/assets/best_f16.tflite").let {
            if (it.exists()) it else File("app/src/main/assets/best_f16.tflite")
        }
        val hostSf = File("/tmp/sf18/stockfish/stockfish-ubuntu-x86-64")
        val pyLibs = File("/tmp/pylibs")

        assertTrue("Sample image must exist", sampleFile.exists())
        assertTrue("Model file must exist", modelFile.exists())

        // Load real PNG pixels using javax.imageio.ImageIO so Robolectric Bitmap has genuine pixels
        val awtImage = ImageIO.read(sampleFile)
        assertNotNull("Decoded AWT image must not be null", awtImage)
        val w = awtImage.width
        val h = awtImage.height
        val argbPixels = IntArray(w * h)
        awtImage.getRGB(0, 0, w, h, argbPixels, 0, w)
        val rawSampleBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        rawSampleBitmap.setPixels(argbPixels, 0, w, 0, 0, w, h)

        val hostLiteRtFactory: (ByteBuffer, VerifiedTfliteModelMetadata, Int) -> RawYoloInferenceBackend =
            { _, meta, threads ->
                object : RawYoloInferenceBackend {
                    override val modelMetadata: VerifiedTfliteModelMetadata = meta
                    override val activeThreads: Int = threads

                    override fun runInference(
                        inputBuffer: ByteBuffer,
                        outputArray: Array<Array<FloatArray>>
                    ) {
                        val inTmp = File.createTempFile("litert_in_", ".bin")
                        val outTmp = File.createTempFile("litert_out_", ".bin")
                        try {
                            val dup = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
                            dup.rewind()
                            val rawBytes = ByteArray(dup.remaining())
                            dup.get(rawBytes)
                            inTmp.writeBytes(rawBytes)

                            val pb = ProcessBuilder(
                                "python3",
                                "-c",
                                """
                                import numpy as np
                                from ai_edge_litert.interpreter import Interpreter
                                interp = Interpreter(model_path="${modelFile.absolutePath}", num_threads=$threads)
                                interp.allocate_tensors()
                                arr = np.fromfile("${inTmp.absolutePath}", dtype=np.float32).reshape((1, 640, 640, 3))
                                interp.set_tensor(interp.get_input_details()[0]["index"], arr)
                                interp.invoke()
                                out = interp.get_tensor(interp.get_output_details()[0]["index"])[0].astype(np.float32)
                                out.tofile("${outTmp.absolutePath}")
                                """.trimIndent()
                            )
                            pb.environment()["PYTHONPATH"] = pyLibs.absolutePath
                            val proc = pb.start()
                            val code = proc.waitFor()
                            check(code == 0) {
                                "Host LiteRT execution failed: ${proc.errorStream.bufferedReader().readText()}"
                            }

                            val outBytes = outTmp.readBytes()
                            val outBuf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)
                            for (c in 0 until 17) {
                                for (i in 0 until 8400) {
                                    outputArray[0][c][i] = outBuf.float
                                }
                            }
                        } finally {
                            inTmp.delete()
                            outTmp.delete()
                        }
                    }

                    override fun close() {}
                }
            }

        if (pyLibs.exists() && hostSf.exists()) {
            val detector = YoloChessDetector(context = context, backendFactory = hostLiteRtFactory)
            val engine = StockfishEngineController(context = context, customExecutablePathProvider = { hostSf })
            val orchestrator = TrainerSessionOrchestrator(context, detector, engine)
            orchestrator.setUserColor(PieceColor.WHITE)

            try {
                val capToScreen = CoordinateTransformer.CaptureToScreenTransform(
                    captureWidth = rawSampleBitmap.width,
                    captureHeight = rawSampleBitmap.height,
                    screenWidthPx = 1080,
                    screenHeightPx = 2280,
                    overlayWindowOffsetX = 0,
                    overlayWindowOffsetY = 0,
                    screenRotation = 0
                )
                val perfConfig = PerformanceMode.MEDIUM.resolveForDevice(
                    availableProcessors = 4,
                    sourceScreenWidth = 1080,
                    sourceScreenHeight = 2280
                )

                var renderedArrow: OverlayArrowCoordinates? = null
                var finalSymbol: OverlayButtonSymbol? = null

                val report = orchestrator.executeSingleFrameAnalysis(
                    requestId = engine.nextRequestId(),
                    capturedBitmap = rawSampleBitmap,
                    captureToScreenTransform = capToScreen,
                    effectiveConfig = perfConfig,
                    onShowArrow = { renderedArrow = it },
                    onClearArrow = { renderedArrow = null },
                    onUpdateButton = { sym, _ -> finalSymbol = sym }
                )

                assertTrue("Expected end-to-end analysis success, got error: ${report.errorOrWarningMessage}", report.success)
                assertEquals(
                    "1k4r1/2p3P1/pp2p2p/8/3P1PnR/P1N1P3/1P2K1P1/2R5 w - - 0 1",
                    report.fen
                )
                assertNotNull(report.bestMoveUci)
                assertNotNull(renderedArrow)
                assertEquals(OverlayButtonSymbol.SUCCESS, finalSymbol)
            } finally {
                orchestrator.stopAllAndRelease()
            }
        }
    }

    @Test
    fun `floating window calibrate button and in-game ManualCalibrationOverlayView save calibrated region accurately`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var analyzeTapped = false
        var calibrateTapped = false

        val floatingButton = com.example.overlay.FloatingChessButtonView(
            context = context,
            onTapAnalyze = { analyzeTapped = true },
            onLongPressClear = {},
            onTapCalibrate = { calibrateTapped = true },
            onDragBy = { _, _ -> }
        )
        floatingButton.layout(0, 0, 68, 102)

        // Tap top circular ♟ section -> triggers analyze
        val downAnalyze = android.view.MotionEvent.obtain(0L, 0L, android.view.MotionEvent.ACTION_DOWN, 34f, 28f, 0)
        val upAnalyze = android.view.MotionEvent.obtain(0L, 50L, android.view.MotionEvent.ACTION_UP, 34f, 28f, 0)
        floatingButton.dispatchTouchEvent(downAnalyze)
        floatingButton.dispatchTouchEvent(upAnalyze)
        assertTrue("Tapping top ♟ button must trigger analyze", analyzeTapped)

        // Tap bottom ⊞ 校准 pill section -> triggers in-game calibration overlay
        val downCal = android.view.MotionEvent.obtain(100L, 100L, android.view.MotionEvent.ACTION_DOWN, 34f, 84f, 0)
        val upCal = android.view.MotionEvent.obtain(100L, 150L, android.view.MotionEvent.ACTION_UP, 34f, 84f, 0)
        floatingButton.dispatchTouchEvent(downCal)
        floatingButton.dispatchTouchEvent(upCal)
        assertTrue("Tapping bottom ⊞ 校准 pill on floating window must open in-game calibration", calibrateTapped)

        // Verify ManualCalibrationOverlayView in-game full-width snap & save-and-analyze
        var savedRect: com.example.model.ManualCalibrationNormalizedRect? = null
        var savedWithAnalyzeNow = false
        val calView = com.example.overlay.ManualCalibrationOverlayView(
            context = context,
            initialRect = null,
            screenWidth = 1080,
            screenHeight = 2340,
            screenRotation = 0,
            onConfirm = { rect, analyzeNow ->
                savedRect = rect
                savedWithAnalyzeNow = analyzeNow
            },
            onCancel = {}
        )
        calView.layout(0, 0, 1080, 2340)
        calView.setBoardPixelRect(0f, 711f, 1080f, 1791f)
        calView.performSaveCalibration(analyzeImmediately = true)

        assertNotNull("Saved calibration rect must not be null", savedRect)
        assertTrue("Save & Analyze must set analyzeImmediately = true", savedWithAnalyzeNow)
        assertEquals(0f, savedRect!!.leftRatio, 0.005f)
        assertEquals(1f, savedRect!!.rightRatio, 0.005f)
        assertEquals(711f / 2340f, savedRect!!.topRatio, 0.005f)
        assertEquals(1791f / 2340f, savedRect!!.bottomRatio, 0.005f)
    }
}

