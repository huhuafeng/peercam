package com.peercam.app.video

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface

/**
 * 相机采集（Camera2）：相机帧 → 编码器 Surface + 本地预览 Surface（可空）。
 */
class CameraSource(
    private val context: Context,
    private val onState: (Boolean) -> Unit
) : CameraDevice.StateCallback() {
    private val log = "PeerCam/Cam"
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var manager: CameraManager? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var encoderSurface: Surface? = null
    private var previewSurface: Surface? = null
    private var facingBack = true
    private var started = false
    private val lock = Any()

    val isStarted: Boolean get() = synchronized(lock) { started }

    val isFacingBack: Boolean get() = synchronized(lock) { facingBack }

    @SuppressLint("MissingPermission")
    fun start(facingBack: Boolean, encoderSurface: Surface, previewSurface: Surface?) {
        synchronized(lock) {
            if (started) return
            this.facingBack = facingBack
            this.encoderSurface = encoderSurface
            this.previewSurface = previewSurface
            started = true
        }
        handlerThread = HandlerThread("pcam-camera").also { it.start() }
        handler = Handler(handlerThread!!.looper)
        manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val id = pickCamera(manager!!, facingBack)
        if (id == null) {
            Log.e(log, "no camera for facing=$facingBack")
            synchronized(lock) {
                started = false
                try { handlerThread?.quitSafely() } catch (_: Exception) {}
                handlerThread = null
                handler = null
            }
            onState(false)
            return
        }
        try {
            manager!!.openCamera(id, this, handler)
        } catch (e: Exception) {
            Log.e(log, "openCamera failed: ${e.message}", e)
            synchronized(lock) {
                started = false
                try { handlerThread?.quitSafely() } catch (_: Exception) {}
                handlerThread = null
                handler = null
            }
            onState(false)
        }
    }

    fun stop() {
        synchronized(lock) {
            shutdownLocked()
            started = false
        }
    }

    /** 切换前后摄像头（先 stop 再 start，由调用方编排）。 */
    fun toggleFacing() {
        synchronized(lock) { facingBack = !facingBack }
    }

    private fun shutdownLocked() {
        try { session?.close() } catch (_: Exception) {}
        try { camera?.close() } catch (_: Exception) {}
        session = null
        camera = null
        try { handlerThread?.quitSafely() } catch (_: Exception) {}
        handlerThread = null
        handler = null
    }

    private fun pickCamera(mgr: CameraManager, back: Boolean): String? {
        try {
            val want = if (back) CameraCharacteristics.LENS_FACING_BACK
            else CameraCharacteristics.LENS_FACING_FRONT
            for (id in mgr.cameraIdList) {
                val lf = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                if (lf == want) return id
            }
            return mgr.cameraIdList.firstOrNull()
        } catch (_: Exception) {
            return null
        }
    }

    override fun onOpened(camera: CameraDevice) {
        synchronized(lock) {
            if (!started) {
                camera.close()
                return
            }
            this.camera = camera
        }
        val encSurf = synchronized(lock) { encoderSurface }
        val prevSurf = synchronized(lock) { previewSurface }
        val handler = this.handler
        if (encSurf == null || handler == null) {
            camera.close()
            onState(false)
            return
        }
        try {
            // 计算合适分辨率（≤1280 中最接近 1280x720 的）
            val ch = manager?.getCameraCharacteristics(camera.id)
            var size = Size(1280, 720)
            try {
                val map: StreamConfigurationMap? =
                    ch?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                if (map != null) {
                    val sizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                    if (sizes.isNotEmpty()) {
                        size = sizes.filter { it.width <= 1280 }
                            .maxByOrNull { it.width * it.height }
                            ?: sizes.maxByOrNull { it.width * it.height }!!
                    }
                }
            } catch (_: Exception) {
            }

            val surfaces = if (prevSurf != null) listOf(encSurf, prevSurf) else listOf(encSurf)

            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(encSurf)
                prevSurf?.let { addTarget(it) }
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(15, 30))
            }.build()

            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    synchronized(lock) {
                        if (!started) {
                            s.close()
                            return
                        }
                        session = s
                    }
                    try {
                        s.setRepeatingRequest(req, null, handler)
                        Log.i(log, "camera configured $size")
                        onState(true)
                    } catch (e: Exception) {
                        Log.e(log, "repeating failed: ${e.message}")
                        onState(false)
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Log.e(log, "capture session failed")
                    onState(false)
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(log, "createCaptureSession failed: ${e.message}", e)
            onState(false)
        }
    }

    override fun onDisconnected(camera: CameraDevice) {
        Log.w(log, "camera disconnected")
        onState(false)
    }

    override fun onError(camera: CameraDevice, error: Int) {
        Log.e(log, "camera error $error")
        onState(false)
    }
}
