package com.peercam.app.net

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * 视频 UDP 通道（全双工单 socket）。
 * 双方都监听 [Protocol.VIDEO_PORT]：本机发送给对方同一端口，天然双向对称。
 */
class VideoChannel(private val logTag: String = "PeerCam/Ch") {
    @Volatile
    var remoteIp: String? = null
        private set

    private var socket: DatagramSocket? = null
    private val lock = Any()

    /** 打开本端接收端口（局域网内可多端并存）。 */
    fun open(port: Int = Protocol.VIDEO_PORT) {
        synchronized(lock) {
            if (socket != null) return
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.broadcast = true
            s.bind(InetSocketAddress(port))
            socket = s
        }
    }

    fun setRemote(ip: String) {
        remoteIp = ip
    }

    val localPort: Int get() = socket?.localPort ?: 0

    private fun sendRaw(targetIp: String?, type: Int, payload: ByteArray = ByteArray(0)) {
        val s = socket ?: return
        val ip = targetIp ?: remoteIp ?: return
        try {
            val h = Protocol.Header().apply {
                this.type = type
                frameId = System.nanoTime()
                timestampMs = android.os.SystemClock.elapsedRealtime()
                payloadLen = payload.size
            }
            val pkt = Protocol.encode(h, payload, 0, payload.size)
            s.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName(ip), Protocol.VIDEO_PORT))
        } catch (e: Exception) {
            Log.w(logTag, "sendRaw($type) failed: ${e.message}")
        }
    }

    /** 发送控制包（CONN/BYE 等），可指定目标。 */
    fun sendControl(targetIp: String?, type: Int, text: String = "") {
        sendRaw(targetIp, type, text.toByteArray(Charsets.UTF_8))
    }

    /** 心跳探测。 */
    fun sendPing() {
        sendRaw(remoteIp, Protocol.TYPE_PING)
    }

    /**
     * 把一整帧 H.264（Annex-B）切成 UDP 分片发出。
     */
    fun sendFrame(frameId: Long, data: ByteArray, isKeyFrame: Boolean, ts: Long) {
        val s = socket ?: return
        val ip = remoteIp ?: return
        val count = (data.size + Protocol.MAX_PAYLOAD - 1) / Protocol.MAX_PAYLOAD
        if (count == 0) return
        val h = Protocol.Header().apply {
            type = Protocol.TYPE_STREAM
            this.frameId = frameId
            fragCount = count
            timestampMs = ts
            flags = if (isKeyFrame) Protocol.FLAG_KEYFRAME else 0
        }
        try {
            for (i in 0 until count) {
                val offset = i * Protocol.MAX_PAYLOAD
                val len = minOf(Protocol.MAX_PAYLOAD, data.size - offset)
                h.fragIndex = i
                var flags = h.flags
                if (i == 0) flags = flags or Protocol.FLAG_FRAME_START
                if (i == count - 1) flags = flags or Protocol.FLAG_FRAME_END
                h.flags = flags
                h.payloadLen = len
                val pkt = Protocol.encode(h, data, offset, len)
                s.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName(ip), Protocol.VIDEO_PORT))
            }
        } catch (e: Exception) {
            Log.w(logTag, "sendFrame failed: ${e.message}")
        }
    }

    /** 发送编码配置（SPS/PPS），带宽高，便于解码端构造格式。 */
    fun sendConfig(width: Int, height: Int, csd: ByteArray) {
        val s = socket ?: return
        val ip = remoteIp ?: return
        try {
            val extra = ByteBufferWrap(width, height, csd)
            val h = Protocol.Header().apply {
                type = Protocol.TYPE_STREAM
                frameId = System.nanoTime()
                fragCount = 1
                timestampMs = android.os.SystemClock.elapsedRealtime()
                flags = Protocol.FLAG_CONFIG or Protocol.FLAG_FRAME_START or Protocol.FLAG_FRAME_END
                payloadLen = extra.size
            }
            val pkt = Protocol.encode(h, extra, 0, extra.size)
            s.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName(ip), Protocol.VIDEO_PORT))
        } catch (e: Exception) {
            Log.w(logTag, "sendConfig failed: ${e.message}")
        }
    }

    private fun ByteBufferWrap(width: Int, height: Int, csd: ByteArray): ByteArray {
        val out = ByteArray(8 + csd.size)
        putInt(out, 0, width)
        putInt(out, 4, height)
        System.arraycopy(csd, 0, out, 8, csd.size)
        return out
    }

    private fun putInt(arr: ByteArray, off: Int, v: Int) {
        arr[off] = (v ushr 24).toByte()
        arr[off + 1] = (v ushr 16).toByte()
        arr[off + 2] = (v ushr 8).toByte()
        arr[off + 3] = v.toByte()
    }

    /**
     * 接收循环（阻塞）。回调给出解析好的东西：
     *  - 完整视频帧 (frameId, data, isKeyFrame)
     *  - 编码配置 (width, height, csd)
     *  - 控制消息 (type, text)
     */
    fun receiveLoop(
        onConfig: (width: Int, height: Int, csd: ByteArray, fromIp: String) -> Unit,
        onFrame: (frameId: Long, data: ByteArray, isKeyFrame: Boolean, ts: Long, fromIp: String) -> Unit,
        onControl: (type: Int, fromIp: String, text: String) -> Unit
    ) {
        val s = socket ?: return
        val buf = ByteArray(65536)
        while (!s.isClosed) {
            try {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val data = buf.copyOf(p.length)
                val h = Protocol.decode(data) ?: continue
                val from = p.address.hostAddress ?: continue
                when (h.type) {
                    Protocol.TYPE_STREAM -> {
                        if (h.flags and Protocol.FLAG_CONFIG != 0) {
                            // 配置包：完整单包
                            if (h.fragIndex == 0 && h.fragCount == 1) {
                                val pay = Protocol.payload(data, h)
                                if (pay.size >= 8) {
                                    val w = ((pay[0].toInt() and 0xFF) shl 24) or
                                        ((pay[1].toInt() and 0xFF) shl 16) or
                                        ((pay[2].toInt() and 0xFF) shl 8) or
                                        (pay[3].toInt() and 0xFF)
                                    val hgt = ((pay[4].toInt() and 0xFF) shl 24) or
                                        ((pay[5].toInt() and 0xFF) shl 16) or
                                        ((pay[6].toInt() and 0xFF) shl 8) or
                                        (pay[7].toInt() and 0xFF)
                                    val csd = pay.copyOfRange(8, pay.size)
                                    onConfig(w, hgt, csd, from)
                                }
                            }
                        } else {
                            val isKey = h.flags and Protocol.FLAG_KEYFRAME != 0
                            val frame = reassemble(h, data)
                            if (frame != null) {
                                onFrame(h.frameId, frame, isKey, h.timestampMs, from)
                            }
                        }
                    }
                    else -> {
                        val text = Protocol.payload(data, h).toString(Charsets.UTF_8)
                        onControl(h.type, from, text)
                    }
                }
            } catch (e: Exception) {
                if (!s.isClosed) Log.e(logTag, "receive error: ${e.message}")
            }
        }
    }

    // ---- 分片重组 ----
    private val fragBuf = HashMap<Long, HashMap<Int, ByteArray>>()
    private val fragMeta = HashMap<Long, IntArray>() // frameId -> [count, receivedAtMs]
    private val FRAG_TTL_MS = 2_000L

    private fun reassemble(h: Protocol.Header, data: ByteArray): ByteArray? {
        synchronized(fragBuf) {
            val now = System.currentTimeMillis()
            // 过期清理：单帧超过 2s 未完成则丢弃（无线内存增长）
            val it = fragMeta.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (now - e.value[1] > FRAG_TTL_MS) {
                    it.remove()
                    fragBuf.remove(e.key)
                }
            }

            val frameId = h.frameId
            val map = fragBuf.getOrPut(frameId) { HashMap() }
            val pay = Protocol.payload(data, h)
            map[h.fragIndex] = pay
            val metas = fragMeta.getOrPut(frameId) { intArrayOf(h.fragCount, now.toInt()) }
            if (map.size < metas[0]) return null
            val total = map.values.sumOf { it.size }
            val out = ByteArray(total)
            var pos = 0
            for (i in 0 until metas[0]) {
                val part = map[i] ?: run {
                    // 缺分片，丢弃该帧
                    fragBuf.remove(frameId)
                    fragMeta.remove(frameId)
                    return null
                }
                System.arraycopy(part, 0, out, pos, part.size)
                pos += part.size
            }
            fragBuf.remove(frameId)
            fragMeta.remove(frameId)
            return out
        }
    }

    fun close() {
        synchronized(lock) {
            try { socket?.close() } catch (_: Exception) {}
            socket = null
        }
    }
}
