package com.example.vision

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class ModelCompatibilityException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

data class TensorDescriptor(
    val index: Int,
    val name: String,
    val shape: List<Int>,
    val dataType: String
)

data class VerifiedTfliteModelMetadata(
    val assetName: String,
    val sizeBytes: Long,
    val sha256Hex: String,
    val description: String,
    val inputTensor: TensorDescriptor,
    val outputTensor: TensorDescriptor
)

/**
 * Verifies the integrity, provenance, and FlatBuffer tensor definitions of `best_f16.tflite`:
 * - Rejects HTML error pages (`<!DOCTYPE`, `<html`) and Git LFS pointer files (`version https://git-lfs...`).
 * - Verifies `TFL3` FlatBuffer magic identifier.
 * - Computes SHA-256 and file size.
 * - Parses subgraph 0 input and output tensors directly from the FlatBuffer and validates compatibility
 *   with YOLOv8 chess detection (`[1, 640, 640, 3]` FLOAT32 -> `[1, 17, 8400]` FLOAT32).
 */
object TfliteModelInspector {

    const val EXPECTED_MODEL_ASSET = "best_f16.tflite"
    const val EXPECTED_SIZE_BYTES = 51_884_986L
    const val EXPECTED_SHA256 = "2bfabe22f91266a2308a7df29c2a22afbbdb537fb2040ef8c93f6ed4fc144033"
    val EXPECTED_INPUT_SHAPE = listOf(1, 640, 640, 3)
    val EXPECTED_OUTPUT_SHAPE = listOf(1, 17, 8400)

    private val TFLITE_TYPES = mapOf(
        0 to "FLOAT32",
        1 to "FLOAT16",
        2 to "INT32",
        3 to "UINT8",
        4 to "INT64",
        5 to "STRING",
        6 to "BOOL",
        7 to "INT16",
        8 to "COMPLEX64",
        9 to "INT8"
    )

    fun inspectAndValidate(
        rawBuffer: ByteBuffer,
        assetName: String = EXPECTED_MODEL_ASSET,
        verifySha256: Boolean = true
    ): VerifiedTfliteModelMetadata {
        val buf = rawBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        buf.rewind()
        val size = buf.remaining()
        if (size < 128) {
            throw ModelCompatibilityException("模型文件过小 ($size bytes)，不是有效的 TFLite 模型")
        }

        val prefixBytes = ByteArray(minOf(size, 96))
        buf.get(prefixBytes)
        buf.rewind()
        val prefixAscii = String(prefixBytes, Charsets.US_ASCII)

        if (prefixAscii.trimStart().startsWith("<!DOCTYPE", ignoreCase = true) ||
            prefixAscii.trimStart().startsWith("<html", ignoreCase = true)
        ) {
            throw ModelCompatibilityException("模型文件为 HTML 网页内容而非二进制模型，拒绝加载")
        }
        if (prefixAscii.startsWith("version https://git-lfs.github.com/spec/v1")) {
            throw ModelCompatibilityException("模型文件为 Git LFS 指针而非真实权重文件，拒绝加载")
        }

        val magic = String(prefixBytes, 4, 4, Charsets.US_ASCII)
        if (magic != "TFL3") {
            throw ModelCompatibilityException("非法的 TFLite FlatBuffer 魔数: '$magic' (预期 'TFL3')")
        }

        val sha256Hex = if (verifySha256) computeSha256(buf) else "SKIPPED"
        buf.rewind()

        // Parse FlatBuffer Model table
        val rootOff = buf.getInt(0)
        val descOff = getFieldOffset(buf, rootOff, 3)
        val description = if (descOff != null) readString(buf, descOff) else ""

        val subgraphsOff = getFieldOffset(buf, rootOff, 2)
            ?: throw ModelCompatibilityException("TFLite 模型缺少 subgraphs 表")
        val (sgVecStart, sgLen) = readVector(buf, subgraphsOff)
        if (sgLen < 1) {
            throw ModelCompatibilityException("TFLite 模型不包含任何计算子图")
        }
        val sg0Off = sgVecStart + buf.getInt(sgVecStart)

        val tensorsOff = getFieldOffset(buf, sg0Off, 0)
            ?: throw ModelCompatibilityException("TFLite 子图缺少 tensors 表")
        val (tensorVecStart, tensorCount) = readVector(buf, tensorsOff)

        val inputsOff = getFieldOffset(buf, sg0Off, 1)
            ?: throw ModelCompatibilityException("TFLite 子图缺少 inputs 列表")
        val (inVecStart, inCount) = readVector(buf, inputsOff)
        if (inCount != 1) {
            throw ModelCompatibilityException("模型输入张量数量不兼容: $inCount (预期 1)")
        }
        val inputIdx = buf.getInt(inVecStart)

        val outputsOff = getFieldOffset(buf, sg0Off, 2)
            ?: throw ModelCompatibilityException("TFLite 子图缺少 outputs 列表")
        val (outVecStart, outCount) = readVector(buf, outputsOff)
        if (outCount != 1) {
            throw ModelCompatibilityException("模型输出张量数量不兼容: $outCount (预期 1)")
        }
        val outputIdx = buf.getInt(outVecStart)

        if (inputIdx !in 0 until tensorCount || outputIdx !in 0 until tensorCount) {
            throw ModelCompatibilityException("模型输入/输出张量索引越界: in=$inputIdx, out=$outputIdx, total=$tensorCount")
        }

        val inputTensor = parseTensor(buf, tensorVecStart, inputIdx)
        val outputTensor = parseTensor(buf, tensorVecStart, outputIdx)

        validateTensorCompatibility(inputTensor, outputTensor)

        return VerifiedTfliteModelMetadata(
            assetName = assetName,
            sizeBytes = size.toLong(),
            sha256Hex = sha256Hex,
            description = description,
            inputTensor = inputTensor,
            outputTensor = outputTensor
        )
    }

    fun validateTensorCompatibility(
        inputTensor: TensorDescriptor,
        outputTensor: TensorDescriptor
    ) {
        if (inputTensor.shape != EXPECTED_INPUT_SHAPE) {
            throw ModelCompatibilityException(
                "输入张量维度不兼容: ${inputTensor.shape} (预期 $EXPECTED_INPUT_SHAPE)"
            )
        }
        if (inputTensor.dataType != "FLOAT32") {
            throw ModelCompatibilityException(
                "输入张量数据类型不兼容: ${inputTensor.dataType} (预期 FLOAT32)"
            )
        }
        if (outputTensor.shape != EXPECTED_OUTPUT_SHAPE) {
            throw ModelCompatibilityException(
                "输出张量维度不兼容: ${outputTensor.shape} (预期 $EXPECTED_OUTPUT_SHAPE)"
            )
        }
        if (outputTensor.dataType != "FLOAT32") {
            throw ModelCompatibilityException(
                "输出张量数据类型不兼容: ${outputTensor.dataType} (预期 FLOAT32)"
            )
        }
    }

    private fun parseTensor(buf: ByteBuffer, tensorVecStart: Int, tensorIndex: Int): TensorDescriptor {
        val elemOff = tensorVecStart + 4 * tensorIndex
        val tOff = elemOff + buf.getInt(elemOff)

        val shapeOff = getFieldOffset(buf, tOff, 0)
        val shape = if (shapeOff != null) {
            val (sStart, sLen) = readVector(buf, shapeOff)
            List(sLen) { i -> buf.getInt(sStart + 4 * i) }
        } else {
            emptyList()
        }

        val typeOff = getFieldOffset(buf, tOff, 1)
        val typeCode = if (typeOff != null) (buf.get(typeOff).toInt() and 0xFF) else 0
        val typeName = TFLITE_TYPES[typeCode] ?: "UNKNOWN($typeCode)"

        val nameOff = getFieldOffset(buf, tOff, 3)
        val name = if (nameOff != null) readString(buf, nameOff) else ""

        return TensorDescriptor(
            index = tensorIndex,
            name = name,
            shape = shape,
            dataType = typeName
        )
    }

    private fun getFieldOffset(buf: ByteBuffer, tableOff: Int, fieldIdx: Int): Int? {
        val vtableRel = buf.getInt(tableOff)
        val vtableOff = tableOff - vtableRel
        val vtableSize = buf.getShort(vtableOff).toInt() and 0xFFFF
        val entryOff = vtableOff + 4 + 2 * fieldIdx
        if (entryOff + 2 > vtableOff + vtableSize) return null
        val fieldRel = buf.getShort(entryOff).toInt() and 0xFFFF
        return if (fieldRel == 0) null else tableOff + fieldRel
    }

    private fun readVector(buf: ByteBuffer, fieldOff: Int): Pair<Int, Int> {
        val uoff = fieldOff + buf.getInt(fieldOff)
        val length = buf.getInt(uoff)
        return Pair(uoff + 4, length)
    }

    private fun readString(buf: ByteBuffer, fieldOff: Int): String {
        val uoff = fieldOff + buf.getInt(fieldOff)
        val length = buf.getInt(uoff)
        val bytes = ByteArray(length)
        val dup = buf.duplicate()
        dup.position(uoff + 4)
        dup.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    fun computeSha256(buf: ByteBuffer): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val dup = buf.duplicate()
        dup.rewind()
        val chunk = ByteArray(65536)
        while (dup.hasRemaining()) {
            val len = minOf(chunk.size, dup.remaining())
            dup.get(chunk, 0, len)
            digest.update(chunk, 0, len)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
