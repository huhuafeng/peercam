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
 * 状态通过 [PeerState] 单例通知 UI。
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
    }

    /** 启动完整会话（相机 + 编码 + UDP 收发 + 心跳）。 */
    private fun startAll(ip: String) {
        if (isConnected.get() || isStopping.get()) return
        remoteIp = ip
        isConnected.set(true)
        lastRxAt = System.currentTimeMillis()
        Log.i(log, "startAll -> $ip")
        PeerState.notifyStatus("连接中 $ip…", true)

        val channel = VideoChannel()
        channel.open()
        channel.setRemote(ip)
        this.channel = channel

        // ---- 编码器 ----
        val enc = Encoder(
            onConfig = { csd ->
                this.channel?.sendConfig(640, 480, csd)
            },
            onFrame = { data, isKey, ts ->
                val ch = this.channel
                if (ch != null) {
                    frameCounter++
                    ch.sendFrame(frameCounter, data, isKey, ts)
                }
            }
        )
        enc.start(640, 480, 1_200_000, 15)
        this.encoder = enc

        // ---- 接收循环 ----
        val ch = channel
        rxThread = Thread({
            ch.receiveLoop(
                onConfig = { w, h, csd ->
                    handler?.post { decoder?.configure(w, h, csd) }
                },
                onFrame = { _, data, isKey, _ ->
                    lastRxAt = System.currentTimeMillis()
                    handler?.post { decoder?.feed(data, isKey) }
                },
                onControl = { type, _, _ ->
                    when (type) {
                        // 对方刚连上或刚重建解码器：请它立刻发关键帧
                        Protocol.TYPE_CONN -> {
                            handler?.post { encoder?.requestKeyFrame() }
                        }
                    }
                }
            )
        }, "pcam-rx").apply { start() }

        // ---- 心跳 ----
        handler?.postDelayed(object : Runnable {
            override fun run() {
                if (isConnected.get() && !isStopping.get()) {
                    channel.sendPing()
                    if (System.currentTimeMillis() - lastRxAt > 10_000) {
                        Log.w(log, "peer silent >10s")
                        PeerState.notifyStatus("对方无响应（可能已退出 App）", false)
                    }
                    handler?.postDelayed(this, 2_000)
                }
            }
        }, 2_000)

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
            if (surface == null) {
                decoder?.stop()
                decoder = null
                return@post
            }
            decoder?.stop()
            decoder = Decoder(surface)
            // 主动请求对端发配置 + 关键帧
            val ch = channel
            val ip = remoteIp
            if (ch != null && ip.isNotEmpty()) {
                ch.sendControl(ip, Protocol.TYPE_CONN)
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
