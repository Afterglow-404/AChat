package com.aftglw.devapi.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 网络在线状态监听器（单例）。
 *
 * 通过 ConnectivityManager 注册 NetworkCallback 实时反映当前是否有可用网络。
 * - 调用 [init] 注册回调（在 Application/MainActivity 入口调用一次即可）
 * - 调用 [isOnline] 同步查询当前状态
 * - 在 Compose 中通过 [online].collectAsState() 观察变化
 *
 * 在缺权限或系统异常时降级为"始终在线"，避免阻塞关键路径。
 */
object NetworkMonitor {

    @Volatile private var initialized = false
    @Volatile private var cm: ConnectivityManager? = null

    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()
    val isOnline: Boolean get() = _online.value

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { refresh() }
        override fun onLost(network: Network) { refresh() }
        override fun onUnavailable() { _online.value = false }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            // capabilities 变化时也刷新（例如从 NET_CAPABILITY_VALIDATED 变化）
            refresh()
        }
    }

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        cm = connectivityManager
        _online.value = computeOnline(connectivityManager)
        try {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(req, callback)
            initialized = true
        } catch (_: Exception) {
            // 缺权限或系统异常时降级为"始终在线"
        }
    }

    private fun refresh() {
        val connectivityManager = cm ?: return
        _online.value = computeOnline(connectivityManager)
    }

    private fun computeOnline(cm: ConnectivityManager): Boolean {
        return try {
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            // 异常时保守返回 true，避免误判阻断用户
            true
        }
    }
}
