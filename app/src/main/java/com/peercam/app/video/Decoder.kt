package com.peercam.app.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * 解码器：Annex-B 输入 → Surface 输出（远端画面）。
 *
 * 实现要点：
 *  - 收到 config（width/height + SPS/PPS）后建解码器
 *  - 之后逐帧 feed（Annex-B 直接送）
 *  - 异常时自动重建，重建后等待下一个关键帧
 */
class Decoder(private val surface: Surface) {
    private val log = "PeerCam/Dec"
    private var codec: MediaCodec? = null
    private var width = 0
    private var height = 0
    private var running = false
    private val lock = Any()

    /**
     * 用 config 初始化解码器。
     * 传 null 表示清空（等待下一个 config）。
     * 同尺寸重复 config 时强制重建（编码器重启后 SPS/PPS 可能变化）。
     */
    fun configure(widthParam: Int, heightParam: Int, csd: ByteArray) {
        synchronized(lock) {
            // 不短路：总是重建（编码器重启/参数变化需要新 CSD）
            releaseLocked()
            try {
                val nalu = H264Util.splitNalus(csd)
                if (nalu.size < 2) {
                    Log.w(log, "csd not enough nalu: ${nalu.size}")
                    return
                }
                val format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, widthParam, heightParam
                )
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
                format.setByteBuffer("csd-0", ByteBuffer.wrap(nalu[0]))
                format.setByteBuffer("csd-1", ByteBuffer.wrap(nalu[1]))

                val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                c.configure(format, surface, null, 0)
                c.start()
                codec = c
                width = widthParam
                height = heightParam
                running = true
                Log.i(log, "decoder configured ${widthParam}x$heightParam")
            } catch (e: Exception) {
                Log.e(log, "configure failed: ${e.message}", e)
                releaseLocked()
            }
        }
    }

    /** 送入一帧（Annex-B 带 start code）。 */
    fun feed(data: ByteArray, isKeyFrame: Boolean) {
        val c = codec ?: return
        try {
            val inIdx = c.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val buf = c.getInputBuffer(inIdx) ?: return
                buf.clear()
                buf.put(data)
                c.queueInputBuffer(
                    inIdx,
                    0,
                    data.size,
                    System.currentTimeMillis() * 1000,
                    if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                )
            }
            val info = MediaCodec.BufferInfo()
            while (true) {
                val oIdx = c.dequeueOutputBuffer(info, 0)
                when {
                    oIdx >= 0 -> {
                        c.releaseOutputBuffer(oIdx, true)
                        continue
                    }
                    oIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        width = if (f.containsKey(MediaFormat.KEY_WIDTH)) f.getInteger(MediaFormat.KEY_WIDTH) else width
                        height = if (f.containsKey(MediaFormat.KEY_HEIGHT)) f.getInteger(MediaFormat.KEY_HEIGHT) else height
                        continue
                    }
                    else -> break
                }
            }
        } catch (e: Exception) {
            Log.w(log, "feed error: ${e.message}")
        }
    }

    private fun releaseLocked() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        running = false
        width = 0; height = 0
    }

    fun stop() {
        synchronized(lock) { releaseLocked() }
    }
}
