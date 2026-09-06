package com.peercam.app.video

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * H.264 工具：AVCC(长度前缀) ↔ Annex-B(start code) 转换、SPS/PPS 提取。
 */
object H264Util {

    /** 是否为 Annex-B：以 00 00 00 01 或 00 00 01 开头。 */
    fun isAnnexB(data: ByteArray, offset: Int = 0): Boolean {
        if (data.size - offset < 4) return false
        val b0 = data[offset].toInt() and 0xFF
        val b1 = data[offset + 1].toInt() and 0xFF
        val b2 = data[offset + 2].toInt() and 0xFF
        val b3 = data[offset + 3].toInt() and 0xFF
        return (b0 == 0 && b1 == 0 && b2 == 0 && b3 == 1) || (b0 == 0 && b1 == 0 && b2 == 1)
    }

    /** 把 AVCC（4 字节大端长度前缀）转成 Annex-B（00 00 00 01）。若已是 Annex-B 原样返回。 */
    fun avccToAnnexB(data: ByteArray, start: Int = 0, len: Int = -1): ByteArray {
        val end = if (len < 0) data.size else start + len
        if (isAnnexB(data, start)) return data.copyOfRange(start, end)
        var pos = start
        val out = java.io.ByteArrayOutputStream()
        while (pos + 4 <= end) {
            val nalLen = ((data[pos].toInt() and 0xFF) shl 24) or
                ((data[pos + 1].toInt() and 0xFF) shl 16) or
                ((data[pos + 2].toInt() and 0xFF) shl 8) or
                (data[pos + 3].toInt() and 0xFF)
            if (nalLen <= 0 || nalLen > end - pos - 4) break // 保护
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(data, pos + 4, nalLen)
            pos += 4 + nalLen
        }
        return out.toByteArray()
    }

    /** 从 Annex-B 流中提取 NAL 单元。 */
    fun splitNalus(data: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        val n = data.size
        // 找 start codes
        val starts = ArrayList<Int>()
        while (i < n - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) { starts.add(i); i += 3; continue }
                if (i + 3 < n && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) { starts.add(i); i += 4; continue }
            }
            i++
        }
        for (s in starts.indices) {
            val begin = starts[s]
            val end = if (s + 1 < starts.size) starts[s + 1] else n
            // 跳过 00 00 00 01 或 00 00 01
            var off = begin
            if (data[off] == 0.toByte() && data[off + 1] == 0.toByte() && data[off + 2] == 1.toByte()) off += 3
            else off += 4
            out.add(data.copyOfRange(off, end))
        }
        return out
    }

    /** 从 MediaFormat 取 csd-0/csd-1（SPS/PPS），拼成 Annex-B config。 */
    fun formatCsdToAnnexB(format: MediaFormat): ByteArray? {
        val sps = csdBuffer(format, "csd-0") ?: return null
        val pps = csdBuffer(format, "csd-1") ?: return null
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 1)); out.write(sps)
        out.write(byteArrayOf(0, 0, 0, 1)); out.write(pps)
        return out.toByteArray()
    }

    private fun csdBuffer(format: MediaFormat, key: String): ByteArray? {
        return try {
            val b = format.getByteBuffer(key) ?: return null
            val arr = ByteArray(b.remaining())
            b.get(arr)
            arr
        } catch (e: Exception) {
            null
        }
    }

    /** 从 Annex-B config（SPS+PPS）解析宽高（用 MediaFormat 的 createVideoFormat 走 csd 解析路径）。 */
    fun parseCsd(csdAnnexB: ByteArray): Triple<Int, Int, ByteArray>? {
        val nalu = splitNalus(csdAnnexB)
        if (nalu.size < 2) return null
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1, 1)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(nalu[0]))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(nalu[1]))
            val w = format.getInteger(MediaFormat.KEY_WIDTH)
            val h = format.getInteger(MediaFormat.KEY_HEIGHT)
            Triple(w, h, csdAnnexB.copyOf())
        } catch (e: Exception) {
            null
        }
    }
}
