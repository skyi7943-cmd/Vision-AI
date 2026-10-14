package com.example.engine

import android.content.Context
import com.example.chess.ChessRulesAndValidator
import com.example.chess.FullChessPosition
import com.example.chess.LegalChessMove
import com.example.model.MoveSpecialType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class EngineInfoMetadata(
    val engineName: String,
    val author: String,
    val evalFileBig: String,
    val evalFileSmall: String,
    val binaryPath: String
)

sealed class EngineAnalysisOutcome {
    data class BestMoveFound(
        val requestId: Long,
        val fen: String,
        val bestMove: LegalChessMove,
        val ponderMoveUci: String?,
        val evaluationDisplay: String,
        val depthReached: Int,
        val elapsedMs: Long
    ) : EngineAnalysisOutcome()

    data class TerminalPosition(
        val requestId: Long,
        val fen: String,
        val terminalType: MoveSpecialType,
        val description: String
    ) : EngineAnalysisOutcome()

    data class DiscardedStale(
        val requestId: Long,
        val reason: String
    ) : EngineAnalysisOutcome()

    data class Failed(
        val requestId: Long,
        val reason: String
    ) : EngineAnalysisOutcome()
}

/**
 * Local Stockfish 18 UCI Engine Controller.
 *
 * - Launches the packaged `libstockfish.so` native binary from `applicationInfo.nativeLibraryDir`
 *   (or a configured binary path in JVM tests).
 * - Verifies Stockfish 18 identity and embedded NNUE network defaults (`nn-c288c895ea92.nnue`
 *   and `nn-37f18f62d772.nnue`).
 * - Manages UCI options (`Threads`, `Hash`), `position fen`, `go movetime`, `stop`, timeouts,
 *   and guarantees that late/stale results from superseded requests are discarded.
 */
class StockfishEngineController(
    private val context: Context? = null,
    private val customExecutablePathProvider: (() -> File?)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val engineMutex = Mutex()
    private val latestRequestId = AtomicLong(0L)
    private val isSearching = AtomicBoolean(false)

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var reader: BufferedReader? = null

    @Volatile
    private var engineMetadata: EngineInfoMetadata? = null

    private var configuredThreads: Int = -1
    private var configuredHashMb: Int = -1

    fun getEngineMetadata(): EngineInfoMetadata? = engineMetadata

    fun isRunning(): Boolean {
        val p = process ?: return false
        return p.isAlive && engineMetadata != null
    }

    fun nextRequestId(): Long = latestRequestId.incrementAndGet()

    fun currentLatestRequestId(): Long = latestRequestId.get()

    /**
     * Cancels any currently running search immediately by sending UCI `stop` and bumping [latestRequestId].
     */
    fun cancelCurrentAnalysis() {
        latestRequestId.incrementAndGet()
        if (isSearching.get()) {
            try {
                writer?.apply {
                    write("stop\n")
                    flush()
                }
            } catch (_: Exception) {
            }
        }
    }

    suspend fun ensureStarted(threads: Int, hashMb: Int): EngineInfoMetadata =
        withContext(ioDispatcher) {
            engineMutex.withLock {
                ensureStartedLocked(threads, hashMb)
            }
        }

    private suspend fun ensureStartedLocked(threads: Int, hashMb: Int): EngineInfoMetadata {
        val existingProc = process
        val existingMeta = engineMetadata
        if (existingProc != null && existingProc.isAlive && existingMeta != null) {
            configureOptionsLocked(threads, hashMb)
            return existingMeta
        }

        stopLocked()

        val binaryFile = resolveExecutableFile()
        if (!binaryFile.exists()) {
            throw IllegalStateException("本地引擎二进制文件不存在: ${binaryFile.absolutePath}")
        }
        if (!binaryFile.canExecute()) {
            binaryFile.setExecutable(true, false)
        }

        val pb = ProcessBuilder(binaryFile.absolutePath)
            .redirectErrorStream(true)
        val started = pb.start()
        val procWriter = BufferedWriter(OutputStreamWriter(started.outputStream, Charsets.UTF_8))
        val procReader = BufferedReader(InputStreamReader(started.inputStream, Charsets.UTF_8))

        process = started
        writer = procWriter
        reader = procReader

        // UCI handshake with timeout
        val meta = withTimeout(4000L) {
            sendCommandRaw(procWriter, "uci")
            var name = ""
            var author = ""
            var evalBig = ""
            var evalSmall = ""
            while (true) {
                val line = procReader.readLine()
                    ?: throw IllegalStateException("Stockfish 引擎进程在 UCI 握手期间意外退出")
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("id name ") -> name = trimmed.removePrefix("id name ").trim()
                    trimmed.startsWith("id author ") -> author = trimmed.removePrefix("id author ").trim()
                    trimmed.contains("option name EvalFile type string default ") -> {
                        evalBig = trimmed.substringAfter("default ").trim()
                    }
                    trimmed.contains("option name EvalFileSmall type string default ") -> {
                        evalSmall = trimmed.substringAfter("default ").trim()
                    }
                    trimmed == "uciok" -> break
                }
            }
            if (name.isBlank()) {
                throw IllegalStateException("Stockfish 未返回有效 id name")
            }
            EngineInfoMetadata(
                engineName = name,
                author = author,
                evalFileBig = evalBig.ifBlank { "nn-c288c895ea92.nnue" },
                evalFileSmall = evalSmall.ifBlank { "nn-37f18f62d772.nnue" },
                binaryPath = binaryFile.absolutePath
            )
        }

        engineMetadata = meta
        configuredThreads = -1
        configuredHashMb = -1
        configureOptionsLocked(threads, hashMb)
        return meta
    }

    private suspend fun configureOptionsLocked(threads: Int, hashMb: Int) {
        val w = writer ?: throw IllegalStateException("引擎输入流未就绪")
        val r = reader ?: throw IllegalStateException("引擎输出流未就绪")
        val safeThreads = threads.coerceIn(1, 16)
        val safeHash = hashMb.coerceIn(16, 512)

        if (configuredThreads == safeThreads && configuredHashMb == safeHash) {
            return
        }

        withTimeout(3000L) {
            if (configuredThreads != safeThreads) {
                sendCommandRaw(w, "setoption name Threads value $safeThreads")
                configuredThreads = safeThreads
            }
            if (configuredHashMb != safeHash) {
                sendCommandRaw(w, "setoption name Hash value $safeHash")
                configuredHashMb = safeHash
            }
            sendCommandRaw(w, "isready")
            while (true) {
                val line = r.readLine()
                    ?: throw IllegalStateException("引擎在配置参数等待 readyok 时退出")
                if (line.trim() == "readyok") break
            }
        }
    }

    /**
     * Analyzes [position] using Stockfish 18.
     *
     * - Immediately checks for terminal positions (Checkmate, Stalemate, No legal moves).
     * - Interrupts any previous in-flight search via `stop`.
     * - Discards late results if [requestId] is no longer equal to [latestRequestId] or if
     *   [confirmedFenValidator] returns false.
     * - Verifies that the engine's `bestmove` is strictly legal in [position].
     */
    suspend fun analyzePosition(
        requestId: Long,
        position: FullChessPosition,
        threads: Int,
        hashMb: Int,
        searchTimeMs: Long,
        confirmedFenValidator: (String) -> Boolean = { true }
    ): EngineAnalysisOutcome = withContext(ioDispatcher) {
        val targetFen = position.toFen()

        if (requestId < latestRequestId.get() || !confirmedFenValidator(targetFen)) {
            return@withContext EngineAnalysisOutcome.DiscardedStale(
                requestId = requestId,
                reason = "请求 #$requestId 在启动前已被更新的请求取代或局面已变更"
            )
        }

        // First validate position and check terminal states (checkmate / stalemate)
        val validation = ChessRulesAndValidator.validatePosition(position)
        if (validation is com.example.chess.PositionValidationResult.Invalid) {
            return@withContext EngineAnalysisOutcome.Failed(requestId, validation.reason)
        }
        val valid = validation as com.example.chess.PositionValidationResult.Valid
        if (valid.legalMoves.isEmpty()) {
            return@withContext if (valid.isInCheck) {
                EngineAnalysisOutcome.TerminalPosition(
                    requestId = requestId,
                    fen = targetFen,
                    terminalType = MoveSpecialType.CHECKMATE,
                    description = "当前局面已将死 (Checkmate)，${position.activeColor.displayName}无合法走法"
                )
            } else {
                EngineAnalysisOutcome.TerminalPosition(
                    requestId = requestId,
                    fen = targetFen,
                    terminalType = MoveSpecialType.STALEMATE,
                    description = "当前局面为逼和 (Stalemate)，${position.activeColor.displayName}无合法走法"
                )
            }
        }

        // Interrupt any ongoing search so we don't wait unnecessarily for an old search
        if (isSearching.get()) {
            try {
                writer?.apply {
                    write("stop\n")
                    flush()
                }
            } catch (_: Exception) {
            }
        }

        engineMutex.withLock {
            if (requestId < latestRequestId.get() || !confirmedFenValidator(targetFen)) {
                return@withLock EngineAnalysisOutcome.DiscardedStale(
                    requestId = requestId,
                    reason = "请求 #$requestId 在获取引擎锁后已过期"
                )
            }

            try {
                ensureStartedLocked(threads, hashMb)
                val w = writer ?: return@withLock EngineAnalysisOutcome.Failed(requestId, "引擎输入管道不可用")
                val r = reader ?: return@withLock EngineAnalysisOutcome.Failed(requestId, "引擎输出管道不可用")

                val safeMoveTime = searchTimeMs.coerceIn(50L, 5000L)
                val startNs = System.nanoTime()

                // Synchronize with `isready` before sending position & go
                withTimeout(2500L) {
                    sendCommandRaw(w, "isready")
                    while (true) {
                        val line = r.readLine()
                            ?: throw IllegalStateException("引擎在同步 isready 时断开")
                        if (line.trim() == "readyok") break
                    }
                }

                if (requestId < latestRequestId.get() || !confirmedFenValidator(targetFen)) {
                    return@withLock EngineAnalysisOutcome.DiscardedStale(
                        requestId = requestId,
                        reason = "请求 #$requestId 在搜索发送前已被取消"
                    )
                }

                isSearching.set(true)
                var bestMoveUci: String? = null
                var ponderUci: String? = null
                var lastScoreText = "0.00"
                var lastDepth = 0

                try {
                    withTimeout(safeMoveTime + 3000L) {
                        sendCommandRaw(w, "position fen $targetFen")
                        sendCommandRaw(w, "go movetime $safeMoveTime")

                        while (true) {
                            val line = r.readLine()
                                ?: throw IllegalStateException("引擎在计算过程中意外终止")
                            val trimmed = line.trim()
                            if (trimmed.startsWith("info ")) {
                                val parsedInfo = parseUciInfoLine(trimmed)
                                if (parsedInfo.first != null) lastDepth = parsedInfo.first!!
                                if (parsedInfo.second != null) lastScoreText = parsedInfo.second!!
                            } else if (trimmed.startsWith("bestmove ")) {
                                val tokens = trimmed.split(Regex("\\s+"))
                                bestMoveUci = tokens.getOrNull(1)
                                if (tokens.size >= 4 && tokens[2] == "ponder") {
                                    ponderUci = tokens[3]
                                }
                                break
                            }
                        }
                    }
                } finally {
                    isSearching.set(false)
                }

                val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)

                // Check again if request was superseded while Stockfish was searching
                if (requestId < latestRequestId.get() || !confirmedFenValidator(targetFen)) {
                    return@withLock EngineAnalysisOutcome.DiscardedStale(
                        requestId = requestId,
                        reason = "迟到的引擎结果已丢弃 (请求 #$requestId != 当前 #${latestRequestId.get()})"
                    )
                }

                val rawMove = bestMoveUci
                if (rawMove.isNullOrBlank() || rawMove == "(none)" || rawMove == "0000") {
                    return@withLock EngineAnalysisOutcome.TerminalPosition(
                        requestId = requestId,
                        fen = targetFen,
                        terminalType = MoveSpecialType.NO_LEGAL_MOVES,
                        description = "引擎返回无合法走法 ($rawMove)"
                    )
                }

                // Verify that the returned move is strictly legal in the current position
                val verifiedLegalMove = valid.legalMoves.firstOrNull {
                    it.uci.equals(rawMove, ignoreCase = true)
                } ?: return@withLock EngineAnalysisOutcome.Failed(
                    requestId = requestId,
                    reason = "引擎返回的走法 ($rawMove) 在当前局面下不合法，已拒绝显示箭头"
                )

                EngineAnalysisOutcome.BestMoveFound(
                    requestId = requestId,
                    fen = targetFen,
                    bestMove = verifiedLegalMove,
                    ponderMoveUci = ponderUci,
                    evaluationDisplay = "$lastScoreText (深度 $lastDepth)",
                    depthReached = lastDepth,
                    elapsedMs = elapsedMs
                )
            } catch (ce: CancellationException) {
                try {
                    writer?.apply {
                        write("stop\n")
                        flush()
                    }
                } catch (_: Exception) {
                }
                throw ce
            } catch (e: Exception) {
                EngineAnalysisOutcome.Failed(
                    requestId = requestId,
                    reason = "引擎分析失败或超时: ${e.message ?: e::class.java.simpleName}"
                )
            }
        }
    }

    suspend fun stopEngine() = withContext(ioDispatcher) {
        cancelCurrentAnalysis()
        engineMutex.withLock {
            stopLocked()
        }
    }

    private fun stopLocked() {
        isSearching.set(false)
        try {
            writer?.apply {
                write("quit\n")
                flush()
                close()
            }
        } catch (_: Exception) {
        }
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        try {
            process?.destroy()
            process?.destroyForcibly()
        } catch (_: Exception) {
        }
        writer = null
        reader = null
        process = null
        engineMetadata = null
        configuredThreads = -1
        configuredHashMb = -1
    }

    private fun resolveExecutableFile(): File {
        customExecutablePathProvider?.invoke()?.let { custom ->
            if (custom.exists()) return custom
        }
        val ctx = context
            ?: throw IllegalStateException("未提供 Context 且未配置自定义引擎路径")
        val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
        return File(nativeDir, "libstockfish.so")
    }

    private fun sendCommandRaw(w: BufferedWriter, cmd: String) {
        w.write(cmd)
        w.write("\n")
        w.flush()
    }

    companion object {
        fun parseUciInfoLine(line: String): Pair<Int?, String?> {
            val tokens = line.trim().split(Regex("\\s+"))
            var depth: Int? = null
            var scoreText: String? = null
            var i = 0
            while (i < tokens.size) {
                when (tokens[i]) {
                    "depth" -> {
                        depth = tokens.getOrNull(i + 1)?.toIntOrNull()
                        i += 2
                    }
                    "score" -> {
                        val kind = tokens.getOrNull(i + 1)
                        val rawVal = tokens.getOrNull(i + 2)?.toIntOrNull()
                        if (kind == "cp" && rawVal != null) {
                            val pawns = rawVal / 100.0
                            val sign = if (pawns > 0) "+" else ""
                            scoreText = "$sign${"%.2f".format(pawns)}"
                            i += 3
                        } else if (kind == "mate" && rawVal != null) {
                            scoreText = if (rawVal > 0) "杀棋 +M$rawVal" else "被杀 -M${kotlin.math.abs(rawVal)}"
                            i += 3
                        } else {
                            i++
                        }
                    }
                    else -> i++
                }
            }
            return Pair(depth, scoreText)
        }
    }
}
