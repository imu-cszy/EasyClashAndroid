package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Intent
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.easytier.EasyTierNativeBridge
import com.github.kr328.clash.service.easytier.Diagnostics
import com.github.kr328.clash.service.easytier.EasyTierConfigStore
import com.github.kr328.clash.service.easytier.EasyTierRuntimeManager
import com.github.kr328.clash.service.easytier.LocalSocksHealthChecker
import com.github.kr328.clash.service.easytier.MihomoConfigComposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * EasyClash 编排模块（P4）：
 *
 *  - 服务启动时（install 前由宿主 Service 调用 [prepare]）：注入 Mihomo 代理链 + 启动 EasyTier；
 *  - 状态变化广播 ACTION_EASYTIER_STATE（EXTRA_STATE_JSON）给 UI 进程；
 *  - 服务销毁时按序停止（宿主 finally 调用 [shutdown]，先于 Clash 资源清理）。
 *
 * 启动顺序遵守 docs/ARCHITECTURE.md §3.1：EasyTier 先行、本地 SOCKS 就绪后放行 Mihomo；
 * 停止顺序相反。
 */
class EasyTierModule(service: Service) : Module<EasyTierModule.Event>(service) {
    sealed class Event {
        data class StatusChanged(val state: String, val detail: String?) : Event()
    }

    companion object {
        /** 当前模块实例（:background 进程内单例；UI 进程不可见，经广播/查询获取状态） */
        @Volatile
        var instance: EasyTierModule? = null
            private set
    }

    private val moduleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val diagnostics = Diagnostics()
    val manager = EasyTierRuntimeManager(
        scope = moduleScope,
        bridge = EasyTierNativeBridge,
        configProvider = { EasyTierConfigStore(service).load(service) },
        healthChecker = LocalSocksHealthChecker(),
        diagnostics = diagnostics,
    )

    /**
     * 宿主 Service 在 clashRuntime block 顶部、任何模块加载前调用：
     * 注入代理链（session 内存，ClashRuntime 的 reset/clearOverride 发生在 block 之前，
     * 不受影响）并启动 EasyTier。返回是否真正启用。
     */
    fun prepare(): Boolean {
        instance = this
        val config = EasyTierConfigStore(service).load(service)
        val errors = config.validate()
        if (!config.enabled || errors.isNotEmpty()) {
            if (errors.isNotEmpty()) {
                diagnostics.log(Diagnostics.Category.CONFIG, "prepare", "invalid config: ${errors.joinToString("; ")}")
            }
            MihomoConfigComposer.apply(config) // enabled=false 或校验失败 -> 清除注入
            return false
        }
        diagnostics.log(Diagnostics.Category.CONFIG, "prepare", "easytier enabled, injecting mihomo chain")
        MihomoConfigComposer.apply(config)
        observeStatus()
        moduleScope.launch {
            manager.start()
        }
        return true
    }

    /** 供 UI 跨进程查询：诊断快照（脱敏） */
    fun diagnosticsExport(): String = diagnostics.exportMasked()

    /**
     * 宿主 Service 销毁路径调用：停止 EasyTier（先于 native 清理）。
     */
    fun shutdown() {
        moduleScope.launch {
            try {
                manager.stop()
            } finally {
                if (instance === this@EasyTierModule) {
                    instance = null
                }
                moduleScope.cancel()
            }
        }
    }

    override suspend fun run() {
        // 诊断查询响应（UI -> :background -> UI），并保持模块存活直至 Service 结束。
        val diagRequest = receiveBroadcast(requireSelf = false) {
            addAction(Intents.ACTION_EASYTIER_REQUEST_DIAG)
        }
        while (true) {
            diagRequest.receive()
            val text = diagnosticsExport()
            val response = Intent(Intents.ACTION_EASYTIER_DIAG)
                .setPackage(service.packageName)
                .putExtra(Intents.EXTRA_DIAG_TEXT, text)
            service.sendBroadcast(response)
            Log.d("EasyTier diagnostics served (${text.length} chars)")
        }
    }

    private fun observeStatus() {
        moduleScope.launch {
            manager.status.collect { status ->
                val payload = buildJsonObject {
                    put("state", status.state.name)
                    put("since", status.since)
                    put("virtual_ipv4", status.virtualIpv4)
                    put("socks5_ok", status.socks5Ok)
                    put("reconnects", status.reconnects)
                    put("last_error", status.lastError)
                    put(
                        "peers",
                        JsonArray(status.peers.map { p ->
                            buildJsonObject {
                                put("hostname", p.hostname)
                                put("ipv4", p.ipv4)
                                put("latency_us", p.latencyUs)
                                put("p2p", p.p2p)
                                put("rx_bytes", p.rxBytes)
                                put("tx_bytes", p.txBytes)
                            }
                        })
                    )
                }
                val intent = Intent(Intents.ACTION_EASYTIER_STATE)
                    .setPackage(service.packageName)
                    .putExtra(Intents.EXTRA_STATE_JSON, payload.toString())
                service.sendBroadcast(intent)
                Log.d("EasyTier state broadcast: ${status.state}")
            }
        }
    }
}
