package com.example.chess

import com.example.model.BoardOrientation
import com.example.model.CastlingRights
import com.example.model.ChessPiece
import com.example.model.MoveSpecialType
import com.example.model.PieceColor
import com.example.model.PieceType
import com.example.vision.CoordinateTransformer
import kotlin.math.abs
import kotlin.math.max

/**
 * 8x8 Chessboard indexed by [rank] (0..7 for '1'..'8') and [file] (0..7 for 'a'..'h').
 */
class ChessBoardState(
    private val squares: Array<Array<ChessPiece?>> = Array(8) { arrayOfNulls<ChessPiece>(8) }
) {
    fun get(file: Int, rank: Int): ChessPiece? {
        if (file !in 0..7 || rank !in 0..7) return null
        return squares[rank][file]
    }

    fun set(file: Int, rank: Int, piece: ChessPiece?) {
        if (file in 0..7 && rank in 0..7) {
            squares[rank][file] = piece
        }
    }

    fun copy(): ChessBoardState {
        val next = Array(8) { r -> Array(8) { f -> squares[r][f] } }
        return ChessBoardState(next)
    }

    fun toPiecePlacementFen(): String {
        val sb = StringBuilder()
        for (rank in 7 downTo 0) {
            var emptyCount = 0
            for (file in 0..7) {
                val p = squares[rank][file]
                if (p == null) {
                    emptyCount++
                } else {
                    if (emptyCount > 0) {
                        sb.append(emptyCount)
                        emptyCount = 0
                    }
                    sb.append(p.fenChar)
                }
            }
            if (emptyCount > 0) sb.append(emptyCount)
            if (rank > 0) sb.append('/')
        }
        return sb.toString()
    }

    fun pieceCount(): Int {
        var count = 0
        for (r in 0..7) {
            for (f in 0..7) {
                if (squares[r][f] != null) count++
            }
        }
        return count
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChessBoardState) return false
        for (r in 0..7) {
            for (f in 0..7) {
                if (squares[r][f] != other.squares[r][f]) return false
            }
        }
        return true
    }

    override fun hashCode(): Int {
        var result = 1
        for (r in 0..7) {
            for (f in 0..7) {
                result = 31 * result + (squares[r][f]?.hashCode() ?: 0)
            }
        }
        return result
    }

    companion object {
        const val INITIAL_PLACEMENT_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"

        fun fromVisualGrid(
            gridPieces: Array<Array<ChessPiece?>>,
            orientation: BoardOrientation
        ): ChessBoardState {
            val board = ChessBoardState()
            for (gridRow in 0..7) {
                for (gridCol in 0..7) {
                    val piece = gridPieces[gridRow][gridCol] ?: continue
                    val sq = CoordinateTransformer.BoardSquare.fromGrid(gridRow, gridCol, orientation)
                    board.set(sq.file, sq.rank, piece)
                }
            }
            return board
        }

        fun fromPiecePlacementFen(placementFen: String): ChessBoardState? {
            val ranks = placementFen.trim().split("/")
            if (ranks.size != 8) return null
            val board = ChessBoardState()
            for ((idx, rankStr) in ranks.withIndex()) {
                val rank = 7 - idx
                var file = 0
                for (ch in rankStr) {
                    if (ch in '1'..'8') {
                        file += (ch - '0')
                    } else {
                        val piece = ChessPiece.fromFenChar(ch) ?: return null
                        if (file > 7) return null
                        board.set(file, rank, piece)
                        file++
                    }
                }
                if (file != 8) return null
            }
            return board
        }
    }
}

data class FullChessPosition(
    val board: ChessBoardState,
    val activeColor: PieceColor,
    val castlingRights: CastlingRights,
    val enPassantSquare: String? = null,
    val halfmoveClock: Int = 0,
    val fullmoveNumber: Int = 1
) {
    fun toFen(): String {
        val placement = board.toPiecePlacementFen()
        val side = activeColor.fenToken
        val castling = castlingRights.toFen()
        val ep = enPassantSquare ?: "-"
        return "$placement $side $castling $ep $halfmoveClock $fullmoveNumber"
    }

    companion object {
        fun fromFen(fen: String): FullChessPosition? {
            val parts = fen.trim().split(Regex("\\s+"))
            if (parts.size < 4) return null
            val board = ChessBoardState.fromPiecePlacementFen(parts[0]) ?: return null
            val color = when (parts[1]) {
                "w" -> PieceColor.WHITE
                "b" -> PieceColor.BLACK
                else -> return null
            }
            val castling = CastlingRights.fromFen(parts[2])
            val ep = if (parts[3] == "-") null else parts[3]
            val halfmove = parts.getOrNull(4)?.toIntOrNull() ?: 0
            val fullmove = parts.getOrNull(5)?.toIntOrNull() ?: 1
            return FullChessPosition(board, color, castling, ep, halfmove, fullmove)
        }
    }
}

data class LegalChessMove(
    val fromFile: Int,
    val fromRank: Int,
    val toFile: Int,
    val toRank: Int,
    val movingPiece: ChessPiece,
    val capturedPiece: ChessPiece? = null,
    val promotionType: PieceType? = null,
    val specialType: MoveSpecialType = MoveSpecialType.NORMAL
) {
    val uci: String
        get() {
            val base = "${('a' + fromFile)}${('1' + fromRank)}${('a' + toFile)}${('1' + toRank)}"
            val promo = promotionType?.algebraicSymbol?.lowercaseChar()?.toString() ?: ""
            return base + promo
        }

    fun promotionLabelChinese(): String? = when (promotionType) {
        PieceType.QUEEN -> "升变为后 (Queen ♕)"
        PieceType.ROOK -> "升变为车 (Rook ♖)"
        PieceType.BISHOP -> "升变为象 (Bishop ♗)"
        PieceType.KNIGHT -> "升变为马 (Knight ♘)"
        else -> null
    }

    fun formatReadableDescription(): String {
        val fromSq = "${('a' + fromFile)}${('1' + fromRank)}"
        val toSq = "${('a' + toFile)}${('1' + toRank)}"
        val pieceName = "${if (movingPiece.color == PieceColor.WHITE) "白" else "黑"}${movingPiece.type.displayName}"
        return when (specialType) {
            MoveSpecialType.CASTLING_KINGSIDE -> "$pieceName $fromSq → $toSq [王翼易位 O-O]"
            MoveSpecialType.CASTLING_QUEENSIDE -> "$pieceName $fromSq → $toSq [后翼易位 O-O-O]"
            MoveSpecialType.EN_PASSANT -> "$pieceName $fromSq × $toSq [吃过路兵 e.p.]"
            MoveSpecialType.PROMOTION -> "$pieceName $fromSq → $toSq [${promotionLabelChinese()}]"
            MoveSpecialType.CAPTURE_PROMOTION -> "$pieceName $fromSq × $toSq (吃${capturedPiece?.type?.displayName ?: "子"}) [${promotionLabelChinese()}]"
            MoveSpecialType.CAPTURE -> "$pieceName $fromSq × $toSq [吃${capturedPiece?.type?.displayName ?: "子"}]"
            else -> "$pieceName $fromSq → $toSq [普通走法]"
        }
    }
}

sealed class PositionValidationResult {
    data class Valid(
        val sanitizedPosition: FullChessPosition,
        val legalMoves: List<LegalChessMove>,
        val isInCheck: Boolean
    ) : PositionValidationResult()

    data class Invalid(
        val reason: String
    ) : PositionValidationResult()
}

/**
 * Complete chess rules, legality checker, and legal move generator.
 */
object ChessRulesAndValidator {

    /**
     * Computes which castling rights are structurally possible given the current board placement
     * (i.e. King and Rook on their original starting squares).
     */
    fun structurallyPossibleCastling(board: ChessBoardState): CastlingRights {
        val wkOnE1 = board.get(4, 0) == ChessPiece.WHITE_KING
        val bkOnE8 = board.get(4, 7) == ChessPiece.BLACK_KING
        return CastlingRights(
            whiteKingSide = wkOnE1 && board.get(7, 0) == ChessPiece.WHITE_ROOK,
            whiteQueenSide = wkOnE1 && board.get(0, 0) == ChessPiece.WHITE_ROOK,
            blackKingSide = bkOnE8 && board.get(7, 7) == ChessPiece.BLACK_ROOK,
            blackQueenSide = bkOnE8 && board.get(0, 7) == ChessPiece.BLACK_ROOK
        )
    }

    /**
     * Checks if an en-passant target square (e.g. `e3` or `d6`) is structurally valid for [activeColor]
     * on [board]. When [requireAdjacentCapturer] is true (used when detecting mid-game ambiguity),
     * also requires an adjacent friendly pawn capable of capturing on that square.
     */
    fun isEnPassantStructurallyValid(
        board: ChessBoardState,
        activeColor: PieceColor,
        epSquareStr: String?,
        requireAdjacentCapturer: Boolean = false
    ): Boolean {
        if (epSquareStr.isNullOrBlank() || epSquareStr == "-") return false
        val sq = CoordinateTransformer.BoardSquare.fromAlgebraic(epSquareStr) ?: return false
        return if (activeColor == PieceColor.WHITE) {
            // Black just pushed a pawn from rank 7 (index 6) to rank 5 (index 4), ep square is rank 6 (index 5)
            val baseValid = sq.rank == 5 &&
                board.get(sq.file, 6) == null &&
                board.get(sq.file, 5) == null &&
                board.get(sq.file, 4) == ChessPiece.BLACK_PAWN
            val hasCapturer = (sq.file > 0 && board.get(sq.file - 1, 4) == ChessPiece.WHITE_PAWN) ||
                (sq.file < 7 && board.get(sq.file + 1, 4) == ChessPiece.WHITE_PAWN)
            baseValid && (!requireAdjacentCapturer || hasCapturer)
        } else {
            // White just pushed a pawn from rank 2 (index 1) to rank 4 (index 3), ep square is rank 3 (index 2)
            val baseValid = sq.rank == 2 &&
                board.get(sq.file, 1) == null &&
                board.get(sq.file, 2) == null &&
                board.get(sq.file, 3) == ChessPiece.WHITE_PAWN
            val hasCapturer = (sq.file > 0 && board.get(sq.file - 1, 3) == ChessPiece.BLACK_PAWN) ||
                (sq.file < 7 && board.get(sq.file + 1, 3) == ChessPiece.BLACK_PAWN)
            baseValid && (!requireAdjacentCapturer || hasCapturer)
        }
    }

    fun validatePosition(position: FullChessPosition): PositionValidationResult {
        val board = position.board
        var whiteKings = 0
        var blackKings = 0
        var wkFile = -1
        var wkRank = -1
        var bkFile = -1
        var bkRank = -1
        var whitePawns = 0
        var blackPawns = 0
        var whiteTotal = 0
        var blackTotal = 0

        for (r in 0..7) {
            for (f in 0..7) {
                val p = board.get(f, r) ?: continue
                if (p.color == PieceColor.WHITE) whiteTotal++ else blackTotal++
                if (p.type == PieceType.PAWN) {
                    if (r == 0 || r == 7) {
                        val sqName = "${('a' + f)}${('1' + r)}"
                        return PositionValidationResult.Invalid(
                            "局面不合法：格 $sqName 上存在兵 (${p.labelName})，兵不能位于第 1 或第 8 横排"
                        )
                    }
                    if (p.color == PieceColor.WHITE) whitePawns++ else blackPawns++
                }
                if (p == ChessPiece.WHITE_KING) {
                    whiteKings++
                    wkFile = f
                    wkRank = r
                } else if (p == ChessPiece.BLACK_KING) {
                    blackKings++
                    bkFile = f
                    bkRank = r
                }
            }
        }

        if (whiteKings != 1 || blackKings != 1) {
            return PositionValidationResult.Invalid(
                "局面不合法：白王数量=$whiteKings，黑王数量=$blackKings (双方必须各有且只有一个王)"
            )
        }

        if (max(abs(wkFile - bkFile), abs(wkRank - bkRank)) <= 1) {
            val wkSq = "${('a' + wkFile)}${('1' + wkRank)}"
            val bkSq = "${('a' + bkFile)}${('1' + bkRank)}"
            return PositionValidationResult.Invalid(
                "局面不合法：白王 ($wkSq) 与黑王 ($bkSq) 相邻"
            )
        }

        if (whitePawns > 8 || blackPawns > 8) {
            return PositionValidationResult.Invalid(
                "局面不合法：兵数量异常 (白兵=$whitePawns, 黑兵=$blackPawns，单方最多 8 个兵)"
            )
        }
        if (whiteTotal > 16 || blackTotal > 16) {
            return PositionValidationResult.Invalid(
                "局面不合法：单方棋子总数超过 16 枚 (白=$whiteTotal, 黑=$blackTotal)"
            )
        }

        // Check that the side NOT to move is not currently in check
        val nonActiveColor = position.activeColor.opposite()
        val nonActiveKingFile = if (nonActiveColor == PieceColor.WHITE) wkFile else bkFile
        val nonActiveKingRank = if (nonActiveColor == PieceColor.WHITE) wkRank else bkRank
        if (isSquareAttackedBy(board, nonActiveKingFile, nonActiveKingRank, position.activeColor)) {
            return PositionValidationResult.Invalid(
                "局面不合法：当前轮到${position.activeColor.displayName}行棋，但对方 (${nonActiveColor.displayName}) 的王正处于被将军状态"
            )
        }

        // Sanitize castling rights and en-passant square against structural board reality
        val structuralCastling = structurallyPossibleCastling(board)
        val sanitizedCastling = CastlingRights(
            whiteKingSide = position.castlingRights.whiteKingSide && structuralCastling.whiteKingSide,
            whiteQueenSide = position.castlingRights.whiteQueenSide && structuralCastling.whiteQueenSide,
            blackKingSide = position.castlingRights.blackKingSide && structuralCastling.blackKingSide,
            blackQueenSide = position.castlingRights.blackQueenSide && structuralCastling.blackQueenSide
        )
        val sanitizedEp = if (isEnPassantStructurallyValid(board, position.activeColor, position.enPassantSquare)) {
            position.enPassantSquare
        } else {
            null
        }

        val sanitizedPos = position.copy(
            castlingRights = sanitizedCastling,
            enPassantSquare = sanitizedEp,
            halfmoveClock = position.halfmoveClock.coerceAtLeast(0),
            fullmoveNumber = position.fullmoveNumber.coerceAtLeast(1)
        )

        val activeKingFile = if (position.activeColor == PieceColor.WHITE) wkFile else bkFile
        val activeKingRank = if (position.activeColor == PieceColor.WHITE) wkRank else bkRank
        val inCheck = isSquareAttackedBy(board, activeKingFile, activeKingRank, nonActiveColor)
        val legalMoves = generateLegalMoves(sanitizedPos)

        return PositionValidationResult.Valid(
            sanitizedPosition = sanitizedPos,
            legalMoves = legalMoves,
            isInCheck = inCheck
        )
    }

    fun isSquareAttackedBy(
        board: ChessBoardState,
        targetFile: Int,
        targetRank: Int,
        attackerColor: PieceColor
    ): Boolean {
        // 1. Pawn attacks
        val pawnAttackFromRank = if (attackerColor == PieceColor.WHITE) targetRank - 1 else targetRank + 1
        val attackerPawn = if (attackerColor == PieceColor.WHITE) ChessPiece.WHITE_PAWN else ChessPiece.BLACK_PAWN
        if (pawnAttackFromRank in 0..7) {
            if (targetFile - 1 >= 0 && board.get(targetFile - 1, pawnAttackFromRank) == attackerPawn) return true
            if (targetFile + 1 <= 7 && board.get(targetFile + 1, pawnAttackFromRank) == attackerPawn) return true
        }

        // 2. Knight attacks
        val attackerKnight = if (attackerColor == PieceColor.WHITE) ChessPiece.WHITE_KNIGHT else ChessPiece.BLACK_KNIGHT
        val knightOffsets = arrayOf(
            intArrayOf(-2, -1), intArrayOf(-2, 1),
            intArrayOf(-1, -2), intArrayOf(-1, 2),
            intArrayOf(1, -2), intArrayOf(1, 2),
            intArrayOf(2, -1), intArrayOf(2, 1)
        )
        for (off in knightOffsets) {
            val nf = targetFile + off[0]
            val nr = targetRank + off[1]
            if (board.get(nf, nr) == attackerKnight) return true
        }

        // 3. King attacks
        val attackerKing = if (attackerColor == PieceColor.WHITE) ChessPiece.WHITE_KING else ChessPiece.BLACK_KING
        for (df in -1..1) {
            for (dr in -1..1) {
                if (df == 0 && dr == 0) continue
                if (board.get(targetFile + df, targetRank + dr) == attackerKing) return true
            }
        }

        // 4. Orthogonal rays (Rook / Queen)
        val attackerRook = if (attackerColor == PieceColor.WHITE) ChessPiece.WHITE_ROOK else ChessPiece.BLACK_ROOK
        val attackerQueen = if (attackerColor == PieceColor.WHITE) ChessPiece.WHITE_QUEEN else ChessPiece.BLACK_QUEEN
        val orthoDirs = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
        for (dir in orthoDirs) {
            var f = targetFile + dir[0]
            var r = targetRank + dir[1]
            while (f in 0..7 && r in 0..7) {
                val p = board.get(f, r)
                if (p != null) {
                    if (p == attackerRook || p == attackerQueen) return true
                    break
                }
                f += dir[0]
                r += dir[1]
            }
        }

        // 5. Diagonal rays (Bishop / Queen)
        val attackerBishop = if (attackerColor == PieceColor.WHITE) ChessPiece.WHITE_BISHOP else ChessPiece.BLACK_BISHOP
        val diagDirs = arrayOf(intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1))
        for (dir in diagDirs) {
            var f = targetFile + dir[0]
            var r = targetRank + dir[1]
            while (f in 0..7 && r in 0..7) {
                val p = board.get(f, r)
                if (p != null) {
                    if (p == attackerBishop || p == attackerQueen) return true
                    break
                }
                f += dir[0]
                r += dir[1]
            }
        }

        return false
    }

    fun generateLegalMoves(position: FullChessPosition): List<LegalChessMove> {
        val board = position.board
        val us = position.activeColor
        val them = us.opposite()
        val pseudo = ArrayList<LegalChessMove>()

        val epTarget = position.enPassantSquare?.let {
            CoordinateTransformer.BoardSquare.fromAlgebraic(it)
        }

        for (r in 0..7) {
            for (f in 0..7) {
                val piece = board.get(f, r) ?: continue
                if (piece.color != us) continue

                when (piece.type) {
                    PieceType.PAWN -> {
                        val dir = if (us == PieceColor.WHITE) 1 else -1
                        val startRank = if (us == PieceColor.WHITE) 1 else 6
                        val promoRank = if (us == PieceColor.WHITE) 7 else 0
                        val nextR = r + dir

                        // Single push
                        if (nextR in 0..7 && board.get(f, nextR) == null) {
                            if (nextR == promoRank) {
                                for (pt in listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)) {
                                    pseudo.add(
                                        LegalChessMove(f, r, f, nextR, piece, null, pt, MoveSpecialType.PROMOTION)
                                    )
                                }
                            } else {
                                pseudo.add(LegalChessMove(f, r, f, nextR, piece))
                                // Double push
                                val doubleR = r + 2 * dir
                                if (r == startRank && board.get(f, doubleR) == null) {
                                    pseudo.add(LegalChessMove(f, r, f, doubleR, piece))
                                }
                            }
                        }

                        // Captures & En Passant
                        for (df in intArrayOf(-1, 1)) {
                            val cf = f + df
                            if (cf !in 0..7 || nextR !in 0..7) continue
                            val targetPiece = board.get(cf, nextR)
                            if (targetPiece != null && targetPiece.color == them) {
                                if (nextR == promoRank) {
                                    for (pt in listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)) {
                                        pseudo.add(
                                            LegalChessMove(
                                                f, r, cf, nextR, piece, targetPiece, pt,
                                                MoveSpecialType.CAPTURE_PROMOTION
                                            )
                                        )
                                    }
                                } else {
                                    pseudo.add(
                                        LegalChessMove(f, r, cf, nextR, piece, targetPiece, null, MoveSpecialType.CAPTURE)
                                    )
                                }
                            } else if (epTarget != null && epTarget.file == cf && epTarget.rank == nextR) {
                                val epCaptured = board.get(cf, r)
                                if (epCaptured != null && epCaptured.color == them && epCaptured.type == PieceType.PAWN) {
                                    pseudo.add(
                                        LegalChessMove(
                                            f, r, cf, nextR, piece, epCaptured, null,
                                            MoveSpecialType.EN_PASSANT
                                        )
                                    )
                                }
                            }
                        }
                    }

                    PieceType.KNIGHT -> {
                        val offsets = arrayOf(
                            intArrayOf(-2, -1), intArrayOf(-2, 1),
                            intArrayOf(-1, -2), intArrayOf(-1, 2),
                            intArrayOf(1, -2), intArrayOf(1, 2),
                            intArrayOf(2, -1), intArrayOf(2, 1)
                        )
                        for (off in offsets) {
                            val nf = f + off[0]
                            val nr = r + off[1]
                            if (nf !in 0..7 || nr !in 0..7) continue
                            val dst = board.get(nf, nr)
                            if (dst == null) {
                                pseudo.add(LegalChessMove(f, r, nf, nr, piece))
                            } else if (dst.color == them) {
                                pseudo.add(LegalChessMove(f, r, nf, nr, piece, dst, null, MoveSpecialType.CAPTURE))
                            }
                        }
                    }

                    PieceType.BISHOP, PieceType.ROOK, PieceType.QUEEN -> {
                        val dirs = when (piece.type) {
                            PieceType.BISHOP -> arrayOf(
                                intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1)
                            )
                            PieceType.ROOK -> arrayOf(
                                intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1)
                            )
                            else -> arrayOf(
                                intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1),
                                intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1)
                            )
                        }
                        for (dir in dirs) {
                            var nf = f + dir[0]
                            var nr = r + dir[1]
                            while (nf in 0..7 && nr in 0..7) {
                                val dst = board.get(nf, nr)
                                if (dst == null) {
                                    pseudo.add(LegalChessMove(f, r, nf, nr, piece))
                                } else {
                                    if (dst.color == them) {
                                        pseudo.add(LegalChessMove(f, r, nf, nr, piece, dst, null, MoveSpecialType.CAPTURE))
                                    }
                                    break
                                }
                                nf += dir[0]
                                nr += dir[1]
                            }
                        }
                    }

                    PieceType.KING -> {
                        for (df in -1..1) {
                            for (dr in -1..1) {
                                if (df == 0 && dr == 0) continue
                                val nf = f + df
                                val nr = r + dr
                                if (nf !in 0..7 || nr !in 0..7) continue
                                val dst = board.get(nf, nr)
                                if (dst == null) {
                                    pseudo.add(LegalChessMove(f, r, nf, nr, piece))
                                } else if (dst.color == them) {
                                    pseudo.add(LegalChessMove(f, r, nf, nr, piece, dst, null, MoveSpecialType.CAPTURE))
                                }
                            }
                        }

                        // Castling moves
                        val homeRank = if (us == PieceColor.WHITE) 0 else 7
                        val kingSideRight = if (us == PieceColor.WHITE) {
                            position.castlingRights.whiteKingSide
                        } else {
                            position.castlingRights.blackKingSide
                        }
                        val queenSideRight = if (us == PieceColor.WHITE) {
                            position.castlingRights.whiteQueenSide
                        } else {
                            position.castlingRights.blackQueenSide
                        }
                        val friendlyRook = if (us == PieceColor.WHITE) ChessPiece.WHITE_ROOK else ChessPiece.BLACK_ROOK

                        if (f == 4 && r == homeRank && !isSquareAttackedBy(board, 4, homeRank, them)) {
                            if (kingSideRight &&
                                board.get(5, homeRank) == null &&
                                board.get(6, homeRank) == null &&
                                board.get(7, homeRank) == friendlyRook &&
                                !isSquareAttackedBy(board, 5, homeRank, them) &&
                                !isSquareAttackedBy(board, 6, homeRank, them)
                            ) {
                                pseudo.add(
                                    LegalChessMove(
                                        4, homeRank, 6, homeRank, piece, null, null,
                                        MoveSpecialType.CASTLING_KINGSIDE
                                    )
                                )
                            }
                            if (queenSideRight &&
                                board.get(3, homeRank) == null &&
                                board.get(2, homeRank) == null &&
                                board.get(1, homeRank) == null &&
                                board.get(0, homeRank) == friendlyRook &&
                                !isSquareAttackedBy(board, 3, homeRank, them) &&
                                !isSquareAttackedBy(board, 2, homeRank, them)
                            ) {
                                pseudo.add(
                                    LegalChessMove(
                                        4, homeRank, 2, homeRank, piece, null, null,
                                        MoveSpecialType.CASTLING_QUEENSIDE
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // Filter pseudo-legal moves to strictly legal moves where own king is not left in check
        return pseudo.filter { move ->
            val nextBoard = applyMoveToBoard(board, move)
            val (kf, kr) = findKing(nextBoard, us) ?: return@filter false
            !isSquareAttackedBy(nextBoard, kf, kr, them)
        }
    }

    fun applyMoveToBoard(board: ChessBoardState, move: LegalChessMove): ChessBoardState {
        val next = board.copy()
        next.set(move.fromFile, move.fromRank, null)
        if (move.specialType == MoveSpecialType.EN_PASSANT) {
            next.set(move.toFile, move.fromRank, null)
        } else if (move.specialType == MoveSpecialType.CASTLING_KINGSIDE) {
            val rook = next.get(7, move.fromRank)
            next.set(7, move.fromRank, null)
            next.set(5, move.fromRank, rook)
        } else if (move.specialType == MoveSpecialType.CASTLING_QUEENSIDE) {
            val rook = next.get(0, move.fromRank)
            next.set(0, move.fromRank, null)
            next.set(3, move.fromRank, rook)
        }

        val placedPiece = if (move.promotionType != null) {
            when (move.movingPiece.color) {
                PieceColor.WHITE -> when (move.promotionType) {
                    PieceType.QUEEN -> ChessPiece.WHITE_QUEEN
                    PieceType.ROOK -> ChessPiece.WHITE_ROOK
                    PieceType.BISHOP -> ChessPiece.WHITE_BISHOP
                    PieceType.KNIGHT -> ChessPiece.WHITE_KNIGHT
                    else -> move.movingPiece
                }
                PieceColor.BLACK -> when (move.promotionType) {
                    PieceType.QUEEN -> ChessPiece.BLACK_QUEEN
                    PieceType.ROOK -> ChessPiece.BLACK_ROOK
                    PieceType.BISHOP -> ChessPiece.BLACK_BISHOP
                    PieceType.KNIGHT -> ChessPiece.BLACK_KNIGHT
                    else -> move.movingPiece
                }
            }
        } else {
            move.movingPiece
        }
        next.set(move.toFile, move.toRank, placedPiece)
        return next
    }

    fun applyLegalMove(position: FullChessPosition, move: LegalChessMove): FullChessPosition {
        val nextBoard = applyMoveToBoard(position.board, move)
        var wk = position.castlingRights.whiteKingSide
        var wq = position.castlingRights.whiteQueenSide
        var bk = position.castlingRights.blackKingSide
        var bq = position.castlingRights.blackQueenSide

        if (move.movingPiece == ChessPiece.WHITE_KING) {
            wk = false
            wq = false
        } else if (move.movingPiece == ChessPiece.BLACK_KING) {
            bk = false
            bq = false
        }
        if ((move.fromFile == 0 && move.fromRank == 0) || (move.toFile == 0 && move.toRank == 0)) wq = false
        if ((move.fromFile == 7 && move.fromRank == 0) || (move.toFile == 7 && move.toRank == 0)) wk = false
        if ((move.fromFile == 0 && move.fromRank == 7) || (move.toFile == 0 && move.toRank == 7)) bq = false
        if ((move.fromFile == 7 && move.fromRank == 7) || (move.toFile == 7 && move.toRank == 7)) bk = false

        val epSq = if (move.movingPiece.type == PieceType.PAWN && abs(move.toRank - move.fromRank) == 2) {
            val midRank = (move.fromRank + move.toRank) / 2
            "${('a' + move.fromFile)}${('1' + midRank)}"
        } else {
            null
        }

        val resetHalfmove = move.movingPiece.type == PieceType.PAWN || move.capturedPiece != null
        val nextHalfmove = if (resetHalfmove) 0 else position.halfmoveClock + 1
        val nextFullmove = if (position.activeColor == PieceColor.BLACK) {
            position.fullmoveNumber + 1
        } else {
            position.fullmoveNumber
        }

        return FullChessPosition(
            board = nextBoard,
            activeColor = position.activeColor.opposite(),
            castlingRights = CastlingRights(wk, wq, bk, bq),
            enPassantSquare = epSq,
            halfmoveClock = nextHalfmove,
            fullmoveNumber = nextFullmove
        )
    }

    fun findLegalMoveByUci(position: FullChessPosition, uciMove: String): LegalChessMove? {
        val clean = uciMove.trim().lowercase()
        return generateLegalMoves(position).firstOrNull { it.uci == clean }
    }

    private fun findKing(board: ChessBoardState, color: PieceColor): Pair<Int, Int>? {
        val target = if (color == PieceColor.WHITE) ChessPiece.WHITE_KING else ChessPiece.BLACK_KING
        for (r in 0..7) {
            for (f in 0..7) {
                if (board.get(f, r) == target) return Pair(f, r)
            }
        }
        return null
    }
}

/**
 * Maintains FEN state across consecutive captures so that we NEVER conflate "recognizing 64 squares"
 * with "having a complete FEN":
 * - Tracks legal moves from a known starting or confirmed position.
 * - Detects mid-game joins and requests user confirmation when castling/en-passant rights are ambiguous.
 * - Detects illegal state jumps across consecutive positions and prompts resynchronization.
 */
class GameStateTracker {

    enum class SyncSource {
        INITIAL_POSITION,
        TRACKED_LEGAL_MOVES,
        MIDGAME_CONFIRMED
    }

    sealed class StateResolution {
        data class Ready(
            val position: FullChessPosition,
            val legalMoves: List<LegalChessMove>,
            val isInCheck: Boolean,
            val provenanceDescription: String
        ) : StateResolution()

        data class RequiresMidgameConfirmation(
            val candidatePosition: FullChessPosition,
            val possibleCastling: CastlingRights,
            val possibleEnPassantSquares: List<String>,
            val reason: String
        ) : StateResolution()

        data class RequiresResync(
            val detectedBoard: ChessBoardState,
            val previousPosition: FullChessPosition,
            val reason: String
        ) : StateResolution()

        data class IllegalPosition(
            val reason: String
        ) : StateResolution()
    }

    private var trackedPosition: FullChessPosition? = null
    private var syncSource: SyncSource? = null
    private var midgameStateConfirmedByUser: Boolean = false
    private var userConfiguredCastling: CastlingRights = CastlingRights.ALL
    private var userConfiguredEnPassant: String? = null
    private var activeColorOverride: PieceColor? = null

    fun setUserConfirmedMidgameState(
        castlingRights: CastlingRights,
        enPassantSquare: String?,
        activeColorOverride: PieceColor? = null
    ) {
        this.userConfiguredCastling = castlingRights
        this.userConfiguredEnPassant = enPassantSquare?.takeIf { it.isNotBlank() && it != "-" }
        this.activeColorOverride = activeColorOverride
        this.midgameStateConfirmedByUser = true
    }

    fun resetAndResync(midgameConfirmed: Boolean = false) {
        trackedPosition = null
        syncSource = null
        midgameStateConfirmedByUser = midgameConfirmed
    }

    fun getTrackedPosition(): FullChessPosition? = trackedPosition

    /**
     * Resolves a newly recognized [board] into a verified [FullChessPosition] for [userColor].
     *
     * By default, when the user clicks the floating ♟ button to request a hint, the side to move
     * defaults to [userColor] (or [activeColorOverride] if explicitly toggled by the user).
     */
    fun resolveRecognizedBoard(
        board: ChessBoardState,
        userColor: PieceColor
    ): StateResolution {
        val targetActiveColor = activeColorOverride ?: userColor
        val placementFen = board.toPiecePlacementFen()

        // Case 1: Standard initial chess position
        if (placementFen == ChessBoardState.INITIAL_PLACEMENT_FEN) {
            val initPos = FullChessPosition(
                board = board.copy(),
                activeColor = targetActiveColor,
                castlingRights = CastlingRights.ALL,
                enPassantSquare = null,
                halfmoveClock = 0,
                fullmoveNumber = 1
            )
            return when (val v = ChessRulesAndValidator.validatePosition(initPos)) {
                is PositionValidationResult.Valid -> {
                    trackedPosition = v.sanitizedPosition
                    syncSource = SyncSource.INITIAL_POSITION
                    StateResolution.Ready(
                        position = v.sanitizedPosition,
                        legalMoves = v.legalMoves,
                        isInCheck = v.isInCheck,
                        provenanceDescription = "标准初始局面自动同步 (行棋方=${targetActiveColor.displayName}, 易位=KQkq, 半回合=0, 回合=1)"
                    )
                }
                is PositionValidationResult.Invalid -> StateResolution.IllegalPosition(v.reason)
            }
        }

        // Case 2: Ongoing tracked game — attempt to match 0, 1, or 2 legal plies from trackedPosition
        val prev = trackedPosition
        if (prev != null) {
            // 0 plies (unchanged board)
            if (prev.board == board) {
                val adjusted = prev.copy(activeColor = targetActiveColor)
                return when (val v = ChessRulesAndValidator.validatePosition(adjusted)) {
                    is PositionValidationResult.Valid -> {
                        trackedPosition = v.sanitizedPosition
                        StateResolution.Ready(
                            position = v.sanitizedPosition,
                            legalMoves = v.legalMoves,
                            isInCheck = v.isInCheck,
                            provenanceDescription = "沿用已确认/已跟踪局面 (行棋方=${targetActiveColor.displayName}, 易位=${v.sanitizedPosition.castlingRights.toFen()}, 过路兵=${v.sanitizedPosition.enPassantSquare ?: "-"})"
                        )
                    }
                    is PositionValidationResult.Invalid -> StateResolution.IllegalPosition(v.reason)
                }
            }

            // 1 legal ply from prev
            val onePlyMatch = ChessRulesAndValidator.generateLegalMoves(prev)
                .map { mv -> mv to ChessRulesAndValidator.applyLegalMove(prev, mv) }
                .firstOrNull { (_, nextPos) -> nextPos.board == board }

            if (onePlyMatch != null) {
                val (mv, nextPos) = onePlyMatch
                val withTargetSide = nextPos.copy(activeColor = targetActiveColor)
                return when (val v = ChessRulesAndValidator.validatePosition(withTargetSide)) {
                    is PositionValidationResult.Valid -> {
                        trackedPosition = v.sanitizedPosition
                        syncSource = SyncSource.TRACKED_LEGAL_MOVES
                        StateResolution.Ready(
                            position = v.sanitizedPosition,
                            legalMoves = v.legalMoves,
                            isInCheck = v.isInCheck,
                            provenanceDescription = "历史合法走法跟踪 (+1半回合: ${mv.uci}, 易位=${v.sanitizedPosition.castlingRights.toFen()}, 过路兵=${v.sanitizedPosition.enPassantSquare ?: "-"}, 半回合=${v.sanitizedPosition.halfmoveClock}, 回合=${v.sanitizedPosition.fullmoveNumber})"
                        )
                    }
                    is PositionValidationResult.Invalid -> StateResolution.IllegalPosition(v.reason)
                }
            }

            // 2 legal plies from prev (e.g. user moved + opponent replied before user clicked button again)
            var twoPlyMatch: Triple<LegalChessMove, LegalChessMove, FullChessPosition>? = null
            outer@ for (mv1 in ChessRulesAndValidator.generateLegalMoves(prev)) {
                val midPos = ChessRulesAndValidator.applyLegalMove(prev, mv1)
                for (mv2 in ChessRulesAndValidator.generateLegalMoves(midPos)) {
                    val endPos = ChessRulesAndValidator.applyLegalMove(midPos, mv2)
                    if (endPos.board == board) {
                        twoPlyMatch = Triple(mv1, mv2, endPos)
                        break@outer
                    }
                }
            }

            if (twoPlyMatch != null) {
                val (mv1, mv2, endPos) = twoPlyMatch
                val withTargetSide = endPos.copy(activeColor = targetActiveColor)
                return when (val v = ChessRulesAndValidator.validatePosition(withTargetSide)) {
                    is PositionValidationResult.Valid -> {
                        trackedPosition = v.sanitizedPosition
                        syncSource = SyncSource.TRACKED_LEGAL_MOVES
                        StateResolution.Ready(
                            position = v.sanitizedPosition,
                            legalMoves = v.legalMoves,
                            isInCheck = v.isInCheck,
                            provenanceDescription = "历史合法走法跟踪 (+2半回合: ${mv1.uci} → ${mv2.uci}, 易位=${v.sanitizedPosition.castlingRights.toFen()}, 过路兵=${v.sanitizedPosition.enPassantSquare ?: "-"}, 半回合=${v.sanitizedPosition.halfmoveClock}, 回合=${v.sanitizedPosition.fullmoveNumber})"
                        )
                    }
                    is PositionValidationResult.Invalid -> StateResolution.IllegalPosition(v.reason)
                }
            }

            // Cannot explain transition via legal moves! Require explicit resync.
            return StateResolution.RequiresResync(
                detectedBoard = board,
                previousPosition = prev,
                reason = "当前棋盘与上一已跟踪局面无法通过 1~2 步合法走法衔接（可能开启了新对局或跳过了多步），请点击「重新同步局面」确认中途状态"
            )
        }

        // Case 3: Mid-game Join (no prior tracked position and not standard initial position)
        val possibleCastling = ChessRulesAndValidator.structurallyPossibleCastling(board)
        val possibleEp = detectStructurallyPossibleEnPassantSquares(board, targetActiveColor)

        val candidateCastling = CastlingRights(
            whiteKingSide = userConfiguredCastling.whiteKingSide && possibleCastling.whiteKingSide,
            whiteQueenSide = userConfiguredCastling.whiteQueenSide && possibleCastling.whiteQueenSide,
            blackKingSide = userConfiguredCastling.blackKingSide && possibleCastling.blackKingSide,
            blackQueenSide = userConfiguredCastling.blackQueenSide && possibleCastling.blackQueenSide
        )
        val candidateEp = userConfiguredEnPassant?.takeIf {
            ChessRulesAndValidator.isEnPassantStructurallyValid(board, targetActiveColor, it)
        }

        val candidatePos = FullChessPosition(
            board = board.copy(),
            activeColor = targetActiveColor,
            castlingRights = candidateCastling,
            enPassantSquare = candidateEp,
            halfmoveClock = 0,
            fullmoveNumber = 1
        )

        // First validate basic legality
        val validation = ChessRulesAndValidator.validatePosition(candidatePos)
        if (validation is PositionValidationResult.Invalid) {
            return StateResolution.IllegalPosition(validation.reason)
        }
        val validResult = validation as PositionValidationResult.Valid

        // If castling or en-passant is structurally possible in this mid-game position AND user hasn't
        // confirmed mid-game state yet, require explicit confirmation rather than silently fabricating!
        if (!midgameStateConfirmedByUser && (possibleCastling.isAnyEnabled() || possibleEp.isNotEmpty())) {
            return StateResolution.RequiresMidgameConfirmation(
                candidatePosition = validResult.sanitizedPosition,
                possibleCastling = possibleCastling,
                possibleEnPassantSquares = possibleEp,
                reason = buildString {
                    append("从中途局面接入：单张截图无法确定历史易位权或过路兵状态。")
                    if (possibleCastling.isAnyEnabled()) {
                        append(" 检测到王与车位于初始格 (可能易位权: ${possibleCastling.toFen()})；")
                    }
                    if (possibleEp.isNotEmpty()) {
                        append(" 存在潜在过路兵目标格 (${possibleEp.joinToString(",")})；")
                    }
                    append(" 请在「局面状态确认」中确认后继续分析。")
                }
            )
        }

        trackedPosition = validResult.sanitizedPosition
        syncSource = SyncSource.MIDGAME_CONFIRMED
        val provenance = "中途局面接入 (行棋方=${targetActiveColor.displayName}[默认所执方], 易位=${validResult.sanitizedPosition.castlingRights.toFen()}, 过路兵=${validResult.sanitizedPosition.enPassantSquare ?: "-"}, 半回合=0/回合=1 初始化规则，未恢复未知历史)"
        return StateResolution.Ready(
            position = validResult.sanitizedPosition,
            legalMoves = validResult.legalMoves,
            isInCheck = validResult.isInCheck,
            provenanceDescription = provenance
        )
    }

    private fun detectStructurallyPossibleEnPassantSquares(
        board: ChessBoardState,
        activeColor: PieceColor
    ): List<String> {
        val rankChar = if (activeColor == PieceColor.WHITE) '6' else '3'
        val candidates = ArrayList<String>()
        for (f in 0..7) {
            val sq = "${('a' + f)}$rankChar"
            if (ChessRulesAndValidator.isEnPassantStructurallyValid(
                    board = board,
                    activeColor = activeColor,
                    epSquareStr = sq,
                    requireAdjacentCapturer = true
                )
            ) {
                candidates.add(sq)
            }
        }
        return candidates
    }
}
