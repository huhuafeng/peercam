package com.peercam.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.peercam.app.databinding.ActivityMainBinding
import com.peercam.app.net.Discovery
import com.peercam.app.net.PeerService
import com.peercam.app.net.PeerState
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), PeerState.Listener {

    private lateinit var binding: ActivityMainBinding
    private var discovery: Discovery? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var remoteSurface: Surface? = null
    private var autoConnectPending = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val cam = result[Manifest.permission.CAMERA] ?: false
            if (!cam) {
                Toast.makeText(this, R.string.permission_camera_denied, Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            startDiscovery()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnConnect.setOnClickListener {
            val ip = binding.etIp.text.toString().trim()
            if (ip.isBlank()) {
                Toast.makeText(this, "请输入对方 IP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            connectTo(ip)
        }

        binding.btnStop.setOnClickListener {
            stopStreaming()
        }

        binding.btnSwitchCamera.setOnClickListener {
            val intent = Intent(this, PeerService::class.java)
                .setAction(PeerService.ACTION_SWITCH_CAMERA)
            ContextCompat.startForegroundService(this, intent)
        }

        binding.textureRemote.surfaceTextureListener =
            object : android.view.TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    st: android.graphics.SurfaceTexture, width: Int, height: Int
                ) {
                    remoteSurface = Surface(st)
                    PeerService.pendingSurface = remoteSurface
                    startService(
                        Intent(this@MainActivity, PeerService::class.java)
                            .setAction(PeerService.ACTION_ATTACH_SURFACE)
                    )
                }

                override fun onSurfaceTextureSizeChanged(
                    st: android.graphics.SurfaceTexture, width: Int, height: Int
                ) {
                }

                override fun onSurfaceTextureDestroyed(
                    st: android.graphics.SurfaceTexture
                ): Boolean {
                    remoteSurface?.release()
                    remoteSurface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(
                    st: android.graphics.SurfaceTexture
                ) {
                }
            }

        refreshMyIp()
        // App 启动即拉起前台服务：提前开 UDP 监听，对方一连接就能互通
        ensureServiceStarted()
    }

    /** App 启动即拉起前台服务（提前开 UDP 监听，等对方连接）。 */
    private fun ensureServiceStarted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(
                this, Intent(this, PeerService::class.java)
            )
        } else {
            startService(Intent(this, PeerService::class.java))
        }
    }

    private fun refreshMyIp() {
        executor.execute {
            val ips = Discovery.localIps()
            runOnUiThread {
                if (ips.isEmpty()) {
                    binding.tvMyIp.text = getString(R.string.label_my_ip)
                } else {
                    binding.tvMyIp.text = "${getString(R.string.label_my_ip)}: ${ips.joinToString("  ")}"
                }
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val list = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = list.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) startDiscovery() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startDiscovery() {
        if (discovery != null) return
        discovery = Discovery(this) { ip, name ->
            runOnUiThread {
                binding.tvFound.text = "发现: $name  $ip  （点击连接）"
                binding.tvFound.setOnClickListener { connectTo(ip) }
            }
        }.also { it.start() }
    }

    /** 若 TextureView surface 已可用，重新 attach（Activity 重启/Service 重启场景）。 */
    private fun reattachSurfaceIfAvailable() {
        if (binding.textureRemote.isAvailable && remoteSurface == null) {
            val st = binding.textureRemote.surfaceTexture ?: return
            remoteSurface = Surface(st)
            PeerService.pendingSurface = remoteSurface
            startService(
                Intent(this, PeerService::class.java)
                    .setAction(PeerService.ACTION_ATTACH_SURFACE)
            )
        }
    }

    private fun connectTo(ip: String) {
        binding.tvStatus.text = "连接 $ip…"
        val intent = Intent(this, PeerService::class.java)
            .setAction(PeerService.ACTION_CONNECT)
            .putExtra(PeerService.EXTRA_IP, ip)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopStreaming() {
        startService(Intent(this, PeerService::class.java).setAction(PeerService.ACTION_DISCONNECT))
    }

    override fun onStart() {
        super.onStart()
        PeerState.addListener(this)
        requestPermissionsIfNeeded()
        // 重新绑定 surface（Activity 重启/Service 重启后 surface 已存在但 Service 未收到 attach）
        reattachSurfaceIfAvailable()
    }

    override fun onStop() {
        PeerState.removeListener(this)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshMyIp()
    }

    override fun onDestroy() {
        discovery?.stop()
        executor.shutdown()
        super.onDestroy()
    }

    override fun onStatus(text: String, connected: Boolean) {
        runOnUiThread {
            binding.tvStatus.text = text
        }
    }

    override fun onCamera(ok: Boolean) {
        runOnUiThread {
            binding.tvStatus.text = if (ok) "本机相机已开启" else "本机相机不可用"
        }
    }
}
