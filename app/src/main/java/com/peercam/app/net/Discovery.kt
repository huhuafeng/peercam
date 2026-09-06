package com.peercam.app.net

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Enumeration

/**
 * 局域网设备发现：UDP 广播 "PCAM|1|<设备名>|<状态>"。
 * 双方每 3s 广播一次，并对收到的广播应答，实现"一边开 App 就能看到对方"。
 */
class Discovery(
    private val context: Context,
    private val deviceName: String = Build.MODEL ?: "Android",
    private val onPeer: (ip: String, name: String) -> Unit
) {
    @Volatile
    private var running = false
    private var socket: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        // 持有 MulticastLock（部分设备不持有收不到广播）
        try {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ml = wifi.createMulticastLock("pcam-discovery")
            ml.setReferenceCounted(false)
            ml.acquire()
            lock = ml
        } catch (_: Exception) {
        }

        try {
            val sock = DatagramSocket(null)
            sock.broadcast = true
            sock.soTimeout = 1500
            sock.reuseAddress = true
            sock.bind(InetSocketAddress(Protocol.DISCOVERY_PORT))
            socket = sock
        } catch (e: Exception) {
            running = false
            return
        }

        thread = Thread {
            val buf = ByteArray(2048)
            while (running) {
                try {
                    val sock = socket ?: break
                    // 广播 Hello
                    sendHello(sock)
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    val text = String(buf, 0, p.length, Charsets.UTF_8)
                    if (!text.startsWith("PCAM|")) continue
                    val parts = text.split("|")
                    if (parts.size < 3) continue
                    val ip = p.address.hostAddress ?: continue
                    if (isSelfIp(ip)) continue
                    val name = try { parts[2] } catch (_: Exception) { "设备" }
                    onPeer(ip, name)
                    // 应答对方（对方也广播，双向都可发现）
                    sendHello(sock)
                } catch (e: SocketTimeoutException) {
                    // 正常超时，继续
                } catch (e: Exception) {
                    if (running) runCatching { Thread.sleep(500) }
                }
            }
        }.apply { name = "pcam-discovery"; start() }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        try { lock?.release() } catch (_: Exception) {}
        socket = null
        try { thread?.join(500) } catch (_: Exception) {}
        thread = null
    }

    private fun sendHello(sock: DatagramSocket) {
        val msg = "PCAM|1|$deviceName|idle"
        val data = msg.toByteArray(Charsets.UTF_8)
        try {
            sock.send(
                DatagramPacket(
                    data, data.size,
                    InetAddress.getByName("255.255.255.255"), Protocol.DISCOVERY_PORT
                )
            )
        } catch (_: Exception) {
        }
    }

    private fun isSelfIp(ip: String): Boolean {
        try {
            val enums: Enumeration<NetworkInterface> = NetworkInterface.getNetworkInterfaces()
            while (enums.hasMoreElements()) {
                val nif = enums.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr.hostAddress == ip) return true
                }
            }
        } catch (_: Exception) {
        }
        return false
    }

    companion object {
        /** 枚举本机局域网 IPv4，优先 Wi-Fi 接口。 */
        fun localIps(): List<String> {
            val out = ArrayList<String>()
            try {
                val enums: Enumeration<NetworkInterface> = NetworkInterface.getNetworkInterfaces()
                while (enums.hasMoreElements()) {
                    val nif = enums.nextElement()
                    if (!nif.isUp || nif.isLoopback) continue
                    for (addr in nif.inetAddresses) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.contains(":")) continue // 跳过 IPv6
                        val isWlan = nif.name.startsWith("wlan") || nif.name.startsWith("wifi")
                        if (isWlan) out.add(0, ip) else out.add(ip)
                    }
                }
            } catch (_: Exception) {
            }
            return out.distinct()
        }
    }
}
