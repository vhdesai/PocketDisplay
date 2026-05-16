package com.seconddisplay.receiver

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * H.264 hardware decoder using MediaCodec.
 * Parses Annex B NAL boundaries and feeds complete NAL units to MediaCodec.
 */
class H264Decoder(
    private val surface: Surface,
    private val onStatusUpdate: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "H264Decoder"
        private const val MIME_TYPE = "video/avc"
        private const val INPUT_TIMEOUT_US = 10000L  // 10ms wait for input buffer
    }

    private var codec: MediaCodec? = null
    private var isConfigured = false
    var videoWidth = 0; private set
    var videoHeight = 0; private set

    // Buffer for accumulating data between NAL boundaries
    private var pendingBuf = ByteArray(256 * 1024)
    private var pendingLen = 0

    var framesDecoded = 0L; private set
    var totalBytes = 0L; private set
    var nalsFedCount = 0L; private set
    var bufferSize = 0; private set

    val hasSps: Boolean get() = spsBytes != null
    val hasPps: Boolean get() = ppsBytes != null
    val configured: Boolean get() = isConfigured
    val nalQueueSize: Int get() = 0

    private var spsBytes: ByteArray? = null
    private var ppsBytes: ByteArray? = null

    var statusText: String = "Waiting..."
        private set

    fun start() {
        Log.i(TAG, "Decoder ready")
        updateStatus("Waiting for SPS/PPS...")
    }

    fun feedData(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        totalBytes += length

        // Drain output first to free up input buffers
        drainOutput()

        // Append incoming data to pending buffer
        ensureCapacity(pendingLen + length)
        System.arraycopy(data, offset, pendingBuf, pendingLen, length)
        pendingLen += length

        // Find and process complete NAL units
        processNalUnits()

        bufferSize = pendingLen
        drainOutput()
    }

    private fun ensureCapacity(needed: Int) {
        if (needed > pendingBuf.size) {
            val newSize = maxOf(needed, pendingBuf.size * 2)
            val newBuf = ByteArray(newSize)
            System.arraycopy(pendingBuf, 0, newBuf, 0, pendingLen)
            pendingBuf = newBuf
        }
    }

    /**
     * Scan pendingBuf for Annex B start codes, extract complete NALs, feed them.
     * Keep the last incomplete NAL (after the last start code) in the buffer.
     */
    private fun processNalUnits() {
        if (pendingLen < 4) return

        // Find all start code positions
        val positions = mutableListOf<Int>()
        var i = 0
        while (i < pendingLen - 3) {
            if (pendingBuf[i] == 0.toByte() && pendingBuf[i + 1] == 0.toByte()) {
                if (pendingBuf[i + 2] == 1.toByte()) {
                    positions.add(i)
                    i += 3
                    continue
                } else if (i + 3 < pendingLen && pendingBuf[i + 2] == 0.toByte() && pendingBuf[i + 3] == 1.toByte()) {
                    positions.add(i)
                    i += 4
                    continue
                }
            }
            i++
        }

        if (positions.size < 2) return // Need at least 2 start codes to have 1 complete NAL

        // Process complete NALs (all except the last one, which may be incomplete)
        for (j in 0 until positions.size - 1) {
            val nalStart = positions[j]
            val nalEnd = positions[j + 1]
            processOneNal(pendingBuf, nalStart, nalEnd - nalStart)
        }

        // Keep the last incomplete NAL in the buffer
        val lastPos = positions[positions.size - 1]
        val remaining = pendingLen - lastPos
        if (lastPos > 0) {
            System.arraycopy(pendingBuf, lastPos, pendingBuf, 0, remaining)
            pendingLen = remaining
        }
    }

    private fun processOneNal(buf: ByteArray, offset: Int, length: Int) {
        if (length < 5) return

        // Determine NAL type
        val scLen = if (buf[offset + 2] == 1.toByte()) 3 else 4
        val nalType = buf[offset + scLen].toInt() and 0x1F

        if (!isConfigured) {
            when (nalType) {
                7 -> { // SPS
                    spsBytes = buf.copyOfRange(offset, offset + length)
                    Log.i(TAG, "SPS found: ${length}B")
                    updateStatus("Got SPS (${length}B)")
                }
                8 -> { // PPS
                    ppsBytes = buf.copyOfRange(offset, offset + length)
                    Log.i(TAG, "PPS found: ${length}B")
                    updateStatus("Got PPS (${length}B)")
                }
            }
            if (spsBytes != null && ppsBytes != null && !isConfigured) {
                configureCodec()
            }
            if (!isConfigured) return
        }

        // Skip repeated SPS/PPS after codec is configured — they confuse some decoders
        if (nalType == 7 || nalType == 8) return

        // Feed IDR frames with KEY_FRAME flag
        val flags = if (nalType == 5) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        feedNalToCodec(buf, offset, length, flags)
    }

    private fun configureCodec(): Boolean {
        val sps = spsBytes ?: return false
        val pps = ppsBytes ?: return false

        return try {
            // Parse resolution from SPS for dynamic resolution support
            val (w, h) = parseSpsResolution(sps)
            val format = MediaFormat.createVideoFormat(MIME_TYPE, w, h)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))

            Log.i(TAG, "Configuring codec ${w}x${h}")
            Log.i(TAG, "  csd-0: ${sps.take(16).joinToString(" ") { "%02x".format(it) }}")
            Log.i(TAG, "  csd-1: ${pps.take(16).joinToString(" ") { "%02x".format(it) }}")

            val c = MediaCodec.createDecoderByType(MIME_TYPE)
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
            isConfigured = true
            videoWidth = w
            videoHeight = h

            Log.i(TAG, "Codec started! Output: ${c.outputFormat}")
            updateStatus("Codec started ${w}x${h}!")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Codec config failed: ${e.message}", e)
            updateStatus("Codec ERROR: ${e.message}")
            false
        }
    }

    /**
     * Feed a single complete NAL unit (with start code) to a MediaCodec input buffer.
     * Retries up to 50ms to avoid dropping NALs.
     */
    private fun feedNalToCodec(data: ByteArray, offset: Int, length: Int, flags: Int = 0) {
        val decoder = codec ?: return
        var retries = 0
        while (retries < 5) {
            try {
                val inputIndex = decoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIndex) ?: return
                    val toCopy = minOf(length, inputBuffer.capacity())
                    inputBuffer.clear()
                    inputBuffer.put(data, offset, toCopy)
                    decoder.queueInputBuffer(inputIndex, 0, toCopy, System.nanoTime() / 1000, flags)
                    nalsFedCount++
                    return
                }
                // No buffer available, drain output and retry
                drainOutput()
                retries++
            } catch (e: Exception) {
                Log.w(TAG, "Feed error: ${e.message}")
                return
            }
        }
        // Still no buffer after retries — drop this NAL
        Log.w(TAG, "Dropped NAL (no input buffer after ${retries} retries)")
    }

    private fun drainOutput() {
        val decoder = codec ?: return
        try {
            val info = MediaCodec.BufferInfo()
            var idx = decoder.dequeueOutputBuffer(info, 0)
            while (idx >= 0) {
                decoder.releaseOutputBuffer(idx, true)
                framesDecoded++
                if (framesDecoded == 1L) {
                    Log.i(TAG, "*** FIRST FRAME DECODED ***")
                    updateStatus("First frame! Decoding...")
                } else if (framesDecoded % 120 == 0L) {
                    updateStatus("Decoded: $framesDecoded frames")
                }
                idx = decoder.dequeueOutputBuffer(info, 0)
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.i(TAG, "Output format: ${decoder.outputFormat}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Drain error: ${e.message}")
        }
    }

    private fun updateStatus(msg: String) {
        statusText = msg
        Log.i(TAG, "Status: $msg")
        onStatusUpdate?.invoke(msg)
    }

    /**
     * Minimal SPS parser — extracts width and height from pic_width/height_in_mbs.
     * Falls back to 960x540 if parsing fails.
     */
    private fun parseSpsResolution(sps: ByteArray): Pair<Int, Int> {
        try {
            // Skip start code to find the raw SPS RBSP
            val scLen = if (sps[2] == 1.toByte()) 3 else 4
            val rbsp = sps.copyOfRange(scLen, sps.size)
            val reader = BitReader(rbsp)

            // forbidden_zero_bit(1), nal_ref_idc(2), nal_unit_type(5)
            reader.skip(8)

            val profileIdc = reader.readBits(8)
            reader.skip(8) // constraint flags + reserved
            reader.skip(8) // level_idc
            reader.readUE() // seq_parameter_set_id

            if (profileIdc in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134)) {
                val chromaFormatIdc = reader.readUE()
                if (chromaFormatIdc == 3) reader.skip(1) // separate_colour_plane_flag
                reader.readUE() // bit_depth_luma_minus8
                reader.readUE() // bit_depth_chroma_minus8
                reader.skip(1) // qpprime_y_zero_transform_bypass_flag
                val scalingMatrixPresent = reader.readBits(1)
                if (scalingMatrixPresent == 1) {
                    val cnt = if (chromaFormatIdc != 3) 8 else 12
                    for (i in 0 until cnt) {
                        if (reader.readBits(1) == 1) { // scaling_list_present_flag
                            val size = if (i < 6) 16 else 64
                            var lastScale = 8; var nextScale = 8
                            for (j in 0 until size) {
                                if (nextScale != 0) {
                                    val delta = reader.readSE()
                                    nextScale = (lastScale + delta + 256) % 256
                                }
                                lastScale = if (nextScale == 0) lastScale else nextScale
                            }
                        }
                    }
                }
            }

            reader.readUE() // log2_max_frame_num_minus4
            val picOrderCntType = reader.readUE()
            if (picOrderCntType == 0) {
                reader.readUE() // log2_max_pic_order_cnt_lsb_minus4
            } else if (picOrderCntType == 1) {
                reader.skip(1) // delta_pic_order_always_zero_flag
                reader.readSE() // offset_for_non_ref_pic
                reader.readSE() // offset_for_top_to_bottom_field
                val numRefFrames = reader.readUE()
                for (i in 0 until numRefFrames) reader.readSE()
            }
            reader.readUE() // max_num_ref_frames
            reader.skip(1) // gaps_in_frame_num_value_allowed_flag
            val picWidthInMbsMinus1 = reader.readUE()
            val picHeightInMapUnitsMinus1 = reader.readUE()
            val frameMbsOnlyFlag = reader.readBits(1)

            val width = (picWidthInMbsMinus1 + 1) * 16
            val height = (picHeightInMapUnitsMinus1 + 1) * 16 * (2 - frameMbsOnlyFlag)

            Log.i(TAG, "SPS parsed: ${width}x${height} (profile=$profileIdc)")
            return Pair(width, height)
        } catch (e: Exception) {
            Log.w(TAG, "SPS parse failed, defaulting to 960x540: ${e.message}")
            return Pair(960, 540)
        }
    }

    /** Simple bit reader for SPS parsing */
    private class BitReader(private val data: ByteArray) {
        private var bytePos = 0
        private var bitPos = 0

        fun skip(n: Int) { repeat(n) { readBit() } }

        private fun readBit(): Int {
            if (bytePos >= data.size) throw IllegalStateException("End of data")
            val bit = (data[bytePos].toInt() shr (7 - bitPos)) and 1
            bitPos++
            if (bitPos == 8) { bitPos = 0; bytePos++ }
            return bit
        }

        fun readBits(n: Int): Int {
            var value = 0
            repeat(n) { value = (value shl 1) or readBit() }
            return value
        }

        fun readUE(): Int {
            var zeros = 0
            while (readBit() == 0) zeros++
            if (zeros == 0) return 0
            return (1 shl zeros) - 1 + readBits(zeros)
        }

        fun readSE(): Int {
            val code = readUE()
            return if (code % 2 == 0) -(code / 2) else (code + 1) / 2
        }
    }

    fun stop() {
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
        codec = null
        isConfigured = false
        Log.i(TAG, "Stopped. Decoded $framesDecoded, fed $nalsFedCount, received ${totalBytes / 1024}KB")
    }

    fun reset() {
        Log.i(TAG, "Resetting decoder")
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
        codec = null
        isConfigured = false
        spsBytes = null
        ppsBytes = null
        pendingLen = 0
        nalsFedCount = 0
        framesDecoded = 0
        updateStatus("Reset - waiting for SPS/PPS...")
    }
}
