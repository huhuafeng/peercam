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
 * 关键设计：Service 创建即打开 UDP 接收端口并开始 receiveLoop（被动接收），
 * 点击"连接"只启动发送侧（相机+编码+定向）——这样一端主动连、另一端无需任何操作即可看到画面。
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

    /** 打开 UDP 端口并启动接收循环（只做被动接收，不启动相机/编码）。 */
    private fun openChannelAndListen() {
        if (channel != null) return
        try {
            val ch = VideoChannel()
            ch.open()
            this.channel = ch
            val chRef = ch
            rxThread = Thread({
                chRef.receiveLoop(
                    onConfig = { w, h, csd ->
                        handler?.post {
                            pendingCsd = Triple(w, h, csd)
                            decoder?.configure(w, h, csd)
                        }
                    },
                    onFrame = { _, data, isKey, _ ->
                        lastRxAt = System.currentTimeMillis()
                        handler?.post { decoder?.feed(data, isKey) }
                    },
                    onControl = { type, from, _ ->
                        when (type) {
                            // 对方请求配置：重发 CSD + 关键帧
                            Protocol.TYPE_CONN -> {
                                handler?.post {
                                    if (from.isNotEmpty()) channel?.setRemote(from)
                                    encoder?.resendConfig()
                                    encoder?.requestKeyFrame()
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

    /** 启动完整会话（发送侧：相机 + 编码 + 定向对端）。 */
    private fun startAll(ip: String) {
        if (isConnected.get() || isStopping.get()) return
        remoteIp = ip
        isConnected.set(true)
        lastRxAt = System.currentTimeMillis()
        Log.i(log, "startAll -> $ip")
        PeerState.notifyStatus("连接中 $ip…", true)

        // 通道必须已打开（onCreate 已保证）；设置目标
        val ch = channel
        if (ch == null) {
            PeerState.notifyStatus("网络通道初始化失败", false)
            return
        }
        ch.setRemote(ip)

        // ---- 编码器 ----
        val enc = Encoder(
            onConfig = { csd ->
                this.channel?.sendConfig(640, 480, csd)
            },
            onFrame = { data, isKey, ts ->
                val c = this.channel
                if (c != null) {
                    frameCounter++
                    c.sendFrame(frameCounter, data, isKey, ts)
                }
            }
        )
        enc.start(640, 480, 1_200_000, 15)
        this.encoder = enc

        // 重建解码器（重连场景：viewSurface 仍在但 decoder 已被 teardown 清空）
        val vs = viewSurface
        if (decoder == null && vs != null) {
            decoder = Decoder(vs)
            pendingCsd?.let { (w, h, csd) -> decoder?.configure(w, h, csd) }
        }

        // ---- 相机 → 编码器 ----
        val cam = CameraSource(this) { ok ->
            PeerState.notifyCamera(ok)
            if (ok) PeerState.notifyStatus("相机就绪，等待对方画面", true)
            else PeerState.notifyStatus("相机不可用", false)
        }
        this.cameraSource = cam
        val surf = enc.encoderSurface
        if (surf != null) {
            cam.start(true, surf, null)
        } else {
            PeerState.notifyStatus("编码器初始化失败", false)
        }
        notifyText(getString(R.string.notify_text, ip))
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
