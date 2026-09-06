package com.peercam.app.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.peercam.app.R
import com.peercam.app.video.CameraSource
import com.peercam.app.video.Decoder
import com.peercam.app.video.Encoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PeerService：会话核心（前台服务，startService 模式）。
 *
 * 数据通路（本端 → 对端）：
 *   Camera2 → Encoder(输入 Surface) → Annex-B → VideoChannel.sendFrame → UDP 1200B 分片 → 对端重组 → Decoder → Surface
 *
 * 双向对称连接模型：
 *  - Service 创建即开 UDP 端口 + 起接收循环（被动接收）
 *  - 收到对方任何包（视频帧/CONN/PING）→ 自动启动本端推流（相机+编码）并定向对方 IP
 *  - 点"连接"则主动发 CONN 触发对方也推流 → 双方无需都点连接，一端点即可互通
 */
class PeerService : Service() {
    private val log = "PeerService"
    private var channel: VideoChannel? = null
    private var encoder: Encoder? = null
    private var decoder: Decoder? = null
    private var cameraSource: CameraSource? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var rxThread: Thread? = null

    private var remoteIp = ""
    private var frameCounter = 0L
    private var lastRxAt = 0L
    private var pendingCsd: Triple<Int, Int, ByteArray>? = null
    private var viewSurface: Surface? = null // UI 最近一次 attach 的 Surface

    // 诊断统计
    private var rxFrameCount = 0L
    private var txFrameCount = 0L
    private var rxConfigCount = 0L
    private var statusUpdater: Runnable? = null
    private val isConnected = AtomicBoolean(false)
    private val isStopping = AtomicBoolean(false)

    companion object {
        const val ACTION_CONNECT = "com.peercam.app.CONNECT"
        const val ACTION_DISCONNECT = "com.peercam.app.DISCONNECT"
        const val ACTION_SWITCH_CAMERA = "com.peercam.app.SWITCH_CAMERA"
        const val ACTION_ATTACH_SURFACE = "com.peercam.app.ATTACH_SURFACE"
        const val EXTRA_IP = "ip"
        const val CHANNEL_ID = "peercam"
        const val NOTIFY_ID = 1

        /** UI 传入的远端输出 Surface（静态桥接，避免 Parcelable 序列化问题）。 */
        @Volatile
        var pendingSurface: Surface? = null
    }

    private fun notifyText(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(
                NOTIFY_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(getString(R.string.notify_title))
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setOngoing(true)
                    .build()
            )
        } catch (_: Exception) {
        }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, getString(R.string.notify_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            ch.description = getString(R.string.notify_channel_desc)
            nm.createNotificationChannel(ch)
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notify_title))
            .setContentText(getString(R.string.notify_text, "启动中"))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(NOTIFY_ID, n)
            }
        } catch (e: Exception) {
            Log.w(log, "startForeground failed: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        handlerThread = HandlerThread("pcam-service").also { it.start() }
        handler = Handler(handlerThread!!.looper)
        startForegroundCompat()
        // 打开通道并启动接收（保证被对方主动连接时能收到视频）
        openChannelAndListen()
    }

    /** 打开 UDP 端口并启动接收循环（被动接收 + 收到对方包自动启动推流）。 */
    private fun openChannelAndListen() {
        if (channel != null) return
        try {
            val ch = VideoChannel()
            ch.open()
            this.channel = ch
            val chRef = ch
            rxThread = Thread({
                chRef.receiveLoop(
                    onConfig = { w, h, csd, fromIp ->
                        handler?.post {
                            rxConfigCount++
                            pendingCsd = Triple(w, h, csd)
                            Log.i(log, "RX config ${w}x$h csd=${csd.size}B (count=$rxConfigCount)")
                            // 收到对方 CSD 说明对方已推流 → 确保本端也推流（对称）
                            startStreamingIfNeeded(from = fromIp)
                            // 解码器可能还没创建（surface attach 时序问题）：用 viewSurface 延迟创建
                            ensureDecoder()
                            // 用 SPS 解析的真实尺寸配置解码器（config 头尺寸可能不准）
                            val real = com.peercam.app.video.H264Util.parseCsd(csd)
                            val rw = real?.first ?: w
                            val rh = real?.second ?: h
                            decoder?.configure(rw, rh, csd)
                        }
                    },
                    onFrame = { _, data, isKey, _, fromIp ->
                        lastRxAt = System.currentTimeMillis()
                        handler?.post {
                            rxFrameCount++
                            if (rxFrameCount % 30 == 0L) Log.i(log, "RX frames=$rxFrameCount")
                            // 对方在推流 → 自动启动本端推流（对称）
                            startStreamingIfNeeded(from = fromIp)
                            ensureDecoder()
                            decoder?.feed(data, isKey)
                            // 诊断：把渲染计数反映到 UI
                            val dec = decoder
                            if (dec != null) {
                                PeerState.notifyStatus(
                                    "互看中 $remoteIp | 发 $txFrameCount 收 $rxFrameCount | 解码输出 ${dec.renderedFrames}", true
                                )
                            }
                        }
                    },
                    onControl = { type, from, _ ->
                        when (type) {
                            // 对方请求我们推流 / 已上线 → 启动本端推流并应答
                            Protocol.TYPE_CONN -> {
                                handler?.post {
                                    startStreamingIfNeeded(from = from)
                                    // 应答（告知对方：我也在推流）
                                    chRef.sendControl(from, Protocol.TYPE_CONN)
                                }
                            }
                            Protocol.TYPE_PING -> {
                                handler?.post {
                                    // 收到心跳 → 应答 PONG，同时确保推流
                                    startStreamingIfNeeded(from = from)
                                    chRef.sendControl(from, Protocol.TYPE_PONG)
                                }
                            }
                        }
                    }
                )
            }, "pcam-rx").apply { start() }
        } catch (e: Exception) {
            Log.e(log, "open channel failed: ${e.message}", e)
        }
    }

    /**
     * 确保本端推流已启动（相机 + 编码 + 定向 [from]）。
     * 若对端来源已知则设置远端 IP；若已启动则仅更新远端 IP。
     * 节流：仅当远端 IP 变化时才重发 CSD（避免每帧触发对端解码器重建）。
     */
    private fun startStreamingIfNeeded(from: String?) {
        if (isStopping.get()) return
        val ch = channel ?: return
        val ipChanged = !from.isNullOrBlank() && remoteIp != from
        if (ipChanged && !from.isNullOrBlank()) {
            remoteIp = from
            ch.setRemote(from)
        }
        val already = isConnected.get()
        if (already) {
            if (ipChanged) {
                // 对端换成新地址（新连接）：重发 CSD + 关键帧
                encoder?.resendConfig()
                encoder?.requestKeyFrame()
            }
            return
        }
        // 启动完整会话（发送侧）
        isConnected.set(true)
        lastRxAt = System.currentTimeMillis()
        Log.i(log, "startStreaming (auto) -> $remoteIp")
        PeerState.notifyStatus("已连接 $remoteIp，正在互看…", true)

        // ---- 编码器 ----
        val enc = Encoder(
            onConfig = { w, h, csd ->
                // 用真实输出尺寸发 config（SPS 内尺寸 vs config 头尺寸必须一致！）
                this.channel?.sendConfig(w, h, csd)
                Log.i(log, "sendConfig ${w}x$h csd=${csd.size}B")
            },
            onFrame = { data, isKey, ts ->
                val c = this.channel
                if (c != null) {
                    frameCounter++
                    txFrameCount++
                    if (txFrameCount % 30 == 0L) Log.i(log, "TX frames=$txFrameCount")
                    c.sendFrame(frameCounter, data, isKey, ts)
                }
            }
        )
        enc.start(640, 480, 1_200_000, 15)
        this.encoder = enc

        // 编码器设置完成，不再在此重建解码器（由 ensureDecoder/attachSurface 负责）

        // ---- 相机 → 编码器 ----
        val cam = CameraSource(this) { ok ->
            PeerState.notifyCamera(ok)
            if (ok) PeerState.notifyStatus("已连接 $remoteIp，正在互看…", true)
            else PeerState.notifyStatus("相机不可用", false)
        }
        this.cameraSource = cam
        val surf = enc.encoderSurface
        if (surf != null) {
            cam.start(true, surf, null)
        } else {
            PeerState.notifyStatus("编码器初始化失败", false)
        }
        notifyText(getString(R.string.notify_text, remoteIp))
        // 通知对方我们也已上线
        if (remoteIp.isNotEmpty()) ch.sendControl(remoteIp, Protocol.TYPE_CONN)

        // 状态刷新：每 1s 显示 TX/RX 统计（诊断用）
        val h1 = handler
        if (h1 != null) {
            val su = object : Runnable {
                override fun run() {
                    if (isConnected.get() && !isStopping.get()) {
                        PeerState.notifyStatus(
                            "互看中 $remoteIp | 发送 ${txFrameCount} 帧 接收 ${rxFrameCount} 帧", true
                        )
                        h1.postDelayed(this, 1_000)
                    }
                }
            }
            statusUpdater = su
            h1.postDelayed(su, 1_000)
        }

        // 心跳：每 2s 发 PING（探测对方在线；对方收到 PING 也会启动推流 = 双保险）
        val h2 = handler
        if (h2 != null) {
            val hb = object : Runnable {
                override fun run() {
                    if (isConnected.get() && !isStopping.get()) {
                        val ipNow = remoteIp
                        if (ipNow.isNotEmpty()) ch.sendPing()
                        // 周期性强制关键帧：UDP 丢包后对端需要 IDR 才能恢复解码（关键！）
                        encoder?.requestKeyFrame()
                        h2.postDelayed(this, 2_000)
                    }
                }
            }
            h2.postDelayed(hb, 2_000)
        }
    }

    /** 点击"连接"主动发起：设置定向 + 通知对方推流。 */
    private fun startAll(ip: String) {
        if (isStopping.get()) return
        val ch = channel ?: return
        if (isConnected.get() && remoteIp == ip) {
            // 已连接同一端：重发请求
            ch.sendControl(ip, Protocol.TYPE_CONN)
            return
        }
        remoteIp = ip
        ch.setRemote(ip)
        PeerState.notifyStatus("连接中 $ip…", true)
        // 先启动本端推流再通知对方
        startStreamingIfNeeded(from = ip)
        ch.sendControl(ip, Protocol.TYPE_CONN)
    }

    /**
     * 确保解码器已创建（用最近一次 attach 的 viewSurface）。
     * 解决 surface attach 与视频包到达的时序竞争问题：
     * 视频包先到而 surface 未 attach 时，推迟到 attach 后也补建。
     */
    private fun ensureDecoder() {
        if (decoder == null && viewSurface != null) {
            decoder = Decoder(viewSurface!!).apply {
                onReady = { ok ->
                    PeerState.notifyStatus(
                        if (ok) "解码器就绪，等待画面…" else "解码器初始化失败", isConnected.get()
                    )
                }
            }
            pendingCsd?.let { (w, h, csd) -> decoder?.configure(w, h, csd) }
        }
    }

    /** 对端画面输出 Surface 变化（重建解码器）。 */
    private fun attachSurface(surface: Surface?) {
        handler?.post {
            viewSurface = surface
            if (surface == null) {
                decoder?.stop()
                decoder = null
                return@post
            }
            decoder?.stop()
            decoder = Decoder(surface)
            // 若已有缓存的 config，立即喂给新解码器
            pendingCsd?.let { (w, h, csd) ->
                decoder?.configure(w, h, csd)
            }
            // 主动请求对端发配置 + 关键帧
            val ch = channel
            val ip = remoteIp
            if (ch != null && ip.isNotEmpty()) {
                ch.sendControl(ip, Protocol.TYPE_CONN)
                encoder?.resendConfig()   // 同时把我们的 CSD 发给对端（对端刚重建解码器）
                encoder?.requestKeyFrame()
            }
        }
    }

    private fun teardownInternal() {
        if (isStopping.getAndSet(true)) return
        isConnected.set(false)
        try { cameraSource?.stop() } catch (_: Exception) {}
        try { encoder?.stop() } catch (_: Exception) {}
        try { decoder?.stop() } catch (_: Exception) {}
        try { rxThread?.interrupt() } catch (_: Exception) {}
        try { channel?.close() } catch (_: Exception) {}
        cameraSource = null
        encoder = null
        decoder = null
        rxThread = null
        channel = null
        remoteIp = ""
        PeerState.clear()
        isStopping.set(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val ip = intent.getStringExtra(EXTRA_IP)
                if (!ip.isNullOrBlank()) handler?.post { startAll(ip) }
            }
            ACTION_DISCONNECT -> handler?.post {
                teardownInternal()
                stopSelf()
            }
            ACTION_SWITCH_CAMERA -> handler?.post {
                val cam = cameraSource ?: return@post
                val enc = encoder ?: return@post
                cam.toggleFacing()
                cam.stop()
                handler?.postDelayed({
                    val surf = enc.encoderSurface
                    if (surf != null) cam.start(cam.isFacingBack, surf, null)
                }, 150)
            }
            ACTION_ATTACH_SURFACE -> {
                attachSurface(pendingSurface)
                pendingSurface = null
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        teardownInternal()
        stopForeground(STOP_FOREGROUND_REMOVE)
        try { handlerThread?.quitSafely() } catch (_: Exception) {}
        handlerThread = null
        handler = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
