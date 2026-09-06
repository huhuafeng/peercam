package com.peercam.app.net

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PeerCam 自有 UDP 协议。
 *
 * 固定包头（大端）共 28 字节：
 *  0..3   magic       "PCAM" (0x5043414D)
 *  4      type        1=STREAM 2=PING 3=PONG 4=BYE 5=CONN
 *  5      reserved
 *  6..9   frameId     帧序号（u32）
 *  10..11 fragIndex   分片序号（u16）
 *  12..13 fragCount   分片总数（u16）
 *  14..15 flags       （u16）
 *  16..23 timestampMs 发送方毫秒时间戳（i64）
 *  24..27 payloadLen  载荷长度（u32）
 */
object Protocol {
    const val DISCOVERY_PORT = 54322
    const val VIDEO_PORT = 54321
    const val MAGIC = 0x5043414D // "PCAM"
    const val HEADER_SIZE = 28

    const val TYPE_STREAM = 1
    const val TYPE_PING = 2
    const val TYPE_PONG = 3
    const val TYPE_BYE = 4
    const val TYPE_CONN = 5

    const val FLAG_FRAME_START = 0x0001
    const val FLAG_FRAME_END = 0x0002
    const val FLAG_KEYFRAME = 0x0004
    const val FLAG_CONFIG = 0x0008

    /** 每个 UDP 分片的最大载荷（1200 < MTU 1500，留足头开销） */
    const val MAX_PAYLOAD = 1200

    class Header {
        var type = 0
        var frameId = 0L
        var fragIndex = 0
        var fragCount = 0
        var flags = 0
        var timestampMs = 0L
        var payloadLen = 0
    }

    /**
     * 组装一个 UDP 包：[header][payload[offset, offset+len)]
     * 注意：header.payloadLen 必须等于 len。
     */
    fun encode(h: Header, payload: ByteArray, offset: Int, len: Int): ByteArray {
        val out = ByteArray(HEADER_SIZE + len)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(MAGIC)
        buf.put(h.type.toByte())
        buf.put(0)
        buf.putInt(h.frameId.toInt())
        buf.putShort(h.fragIndex.toShort())
        buf.putShort(h.fragCount.toShort())
        buf.putShort(h.flags.toShort())
        buf.putLong(h.timestampMs)
        buf.putInt(len)
        System.arraycopy(payload, offset, out, HEADER_SIZE, len)
        return out
    }

    /** 解析包头；magic 不符或长度不足返回 null。 */
    fun decode(data: ByteArray): Header? {
        if (data.size < HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(data, 0, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        if (buf.int != MAGIC) return null
        val h = Header()
        h.type = buf.get().toInt()
        buf.get() // reserved
        h.frameId = buf.int.toLong() and 0xFFFFFFFFL
        h.fragIndex = buf.short.toInt() and 0xFFFF
        h.fragCount = buf.short.toInt() and 0xFFFF
        h.flags = buf.short.toInt() and 0xFFFF
        h.timestampMs = buf.long
        h.payloadLen = buf.int
        // 校验 payload 长度：防恶意/损坏包（payloadLen 非法导致 copyOfRange 崩溃）
        if (h.payloadLen < 0 || data.size < HEADER_SIZE + h.payloadLen) return null
        return h
    }

    /** 取载荷部分。 */
    fun payload(data: ByteArray, h: Header): ByteArray =
        data.copyOfRange(HEADER_SIZE, HEADER_SIZE + h.payloadLen)
}
