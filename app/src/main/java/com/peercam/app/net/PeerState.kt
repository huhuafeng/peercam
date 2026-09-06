package com.peercam.app.net

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 轻量状态桥：Service → UI。
 * 服务是 startService 模式（无 binder），用单例 + 监听列表做 UI 通知。
 */
object PeerState {
    @Volatile
    var connected: Boolean = false

    @Volatile
    var remoteIp: String = ""

    interface Listener {
        fun onStatus(text: String, connected: Boolean)
        fun onCamera(ok: Boolean)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun notifyStatus(text: String, connected: Boolean) {
        this.connected = connected
        listeners.forEach { it.onStatus(text, connected) }
    }

    fun notifyCamera(ok: Boolean) {
        listeners.forEach { it.onCamera(ok) }
    }

    fun clear() {
        connected = false
        remoteIp = ""
    }
}
