package com.peercam.app.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface

/**
 * H.264 编码器：相机 Surface 输入 → Annex-B 输出回调。
 *
 *  - 内部创建输入 Surface（[encoderSurface]），Camera2 直接把帧送进来
 *  - 参数集（SPS/PPS）变化时回调 [onConfig]（含解析出的真实宽高）
 *  - 编码帧回调 [onFrame]，已统一为 Annex-B 格式
 */
class Encoder(
    private val onConfig: (width: Int, height: Int, csd: ByteArray) -> Unit,
    private val onFrame: (data: ByteArray, isKeyFrame: Boolean, ts: Long) -> Unit
) {
    private val log = "PeerCam/Enc"
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var running = false
    private val lock = Any()

    /** 相机输出目标 Surface。 */
    val encoderSurface: Surface?
        get() = synchronized(lock) { inputSurface }

    val isRunning: Boolean get() = synchronized(lock) { running }

    /**
     * 启动编码器（可重复调用；已启动则 no-op）。
     */
    fun start(width: Int = 640, height: Int = 480, bitrate: Int = 1_200_000, fps: Int = 15) {
        synchronized(lock) {
            if (running) return
            try {
                val format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, width, height
                ).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 每 1s 一个关键帧，便于快速恢复
                    setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000) // 编码器停帧仍出重复帧(100ms)
                    setInteger(
                        MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                    )
                    setInteger(
                        MediaFormat.KEY_LEVEL,
                        MediaCodecInfo.CodecProfileLevel.AVCLevel31
                    )
                }
                val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = c.createInputSurface()

                c.setCallback(object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                        // Surface 输入模式，无 input buffer
                    }

                    override fun onOutputBufferAvailable(
                        codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo
                    ) {
                        try {
                            // 标准方式：getOutputBuffer 返回的 buffer position/limit 指向本帧数据
                            val outBuf = codec.getOutputBuffer(index) ?: return
                            val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            val isCfg = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

                            // 读取本帧数据（buffer.remaining() 即 info.size，无需手动 position/limit）
                            val arr = ByteArray(outBuf.remaining())
                            outBuf.get(arr)

                            if (isCfg && arr.size > 0) {
                                val csd = H264Util.avccToAnnexB(arr)
                                latestCsd = csd
                                val parsed = H264Util.parseCsd(csd)
                                val w = parsed?.first ?: width
                                val h = parsed?.second ?: height
                                onConfig(w, h, csd)
                                Log.i(log, "encoder config sent (${csd.size}B ${w}x$h)")
                            } else if (info.size > 0) {
                                // avccToAnnexB 内部：Annex-B 直通；AVCC 严格转换；失败回退原始
                                val annexB = H264Util.avccToAnnexB(arr)
                                onFrame(annexB, isKey, info.presentationTimeUs / 1000)
                            }
                        } catch (e: Exception) {
                            Log.w(log, "output: ${e.message}")
                        } finally {
                            try { codec.releaseOutputBuffer(index, false) } catch (_: Exception) {}
                        }
                    }

                    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                        Log.e(log, "encoder error: ${e.message}")
                    }

                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                        val csd = H264Util.formatCsdToAnnexB(format)
                        if (csd != null) {
                            latestCsd = csd
                            // 从 SPS 解析真实输出尺寸（Surface 输入时编码器实际尺寸）
                            val parsed = H264Util.parseCsd(csd)
                            val w = parsed?.first ?: format.getInteger(MediaFormat.KEY_WIDTH)
                            val h = parsed?.second ?: format.getInteger(MediaFormat.KEY_HEIGHT)
                            Log.i(log, "encoder real output: ${w}x$h")
                            try { onConfig(w, h, csd) } catch (_: Exception) {}
                        }
                    }
                })
                c.start()
                codec = c
                running = true
                Log.i(log, "encoder started ${width}x$height @ ${bitrate / 1000}kbps ${fps}fps")
            } catch (e: Exception) {
                Log.e(log, "encoder start failed: ${e.message}", e)
                releaseLocked()
            }
        }
    }

    /** 最近一次编码器输出的 CSD（SPS/PPS Annex-B），可重复发送。 */
    private var latestCsd: ByteArray? = null

    /** 重发参数集给对端（解决解码器重建后无 CSD 导致黑屏）。 */
    fun resendConfig() {
        val c = latestCsd ?: return
        val parsed = H264Util.parseCsd(c)
        val w = parsed?.first ?: width
        val h = parsed?.second ?: height
        try { onConfig(w, h, c) } catch (_: Exception) {}
    }

    fun requestKeyFrame() {
        synchronized(lock) {
            try {
                val b = Bundle()
                b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                codec?.setParameters(b)
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        synchronized(lock) { releaseLocked() }
    }

    private fun releaseLocked() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        inputSurface = null
        running = false
    }
}
