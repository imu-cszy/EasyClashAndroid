package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Intent
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.easytier.EasyTierNativeBridge
import com.github.kr328.clash.core.model.ProxySort
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
import kotlinx.coroutines.withTimeoutOrNull
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
        /** prepare() 等待 EasyTier 就绪的上限（失败不阻塞 VPN 启动） */
        private const val PREPARE_TIMEOUT_MS = 20_000L

        /** 当前模块实例（:background 进程内单例；UI 进程不可见，经广播/查询获取状态） */
        @Volatile
        var instance: EasyTierModule? = null
            private set
    }

    private val moduleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val diagnostics = Diagnostics()

    /** Mihomo 代理链是否已校验过（一次性） */
    private var chainVerified = false
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
     * 不受影响）并**等待** EasyTier 就绪（实例运行 + 本地 SOCKS 监听），
     * 这样 TunModule 打开 VPN 之前 EasyTier 的 underlay socket 已经建立（走物理网络，
     * 不会被随后建立的 TUN 捕获）。等待有上限，失败不阻塞服务。
     *
     * @return 是否真正启用并就绪
     */
    suspend fun prepare(): Boolean {
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
        return withTimeoutOrNull(PREPARE_TIMEOUT_MS) { manager.start() } ?: false
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

    /**
     * 校验 Mihomo 代理链是否真的注入成功：AWS-EXIT 组存在且成员为两个 AWS 出站。
     * 失败最常见原因：profile 加载失败、或注入时机晚于配置解析。
     */
    private fun verifyMihomoChain() {
        val groups = runCatching { Clash.queryGroupNames(false) }.getOrNull()
        if (groups == null) {
            diagnostics.log(Diagnostics.Category.MIHOMO, "chain", "query group names failed")
            return
        }
        val hasExit = MihomoConfigComposer.GROUP_AWS_EXIT in groups
        diagnostics.log(
            Diagnostics.Category.MIHOMO, "chain",
            "AWS-EXIT present=$hasExit groups=${groups.take(12)}"
        )
        if (hasExit) {
            val members = runCatching {
                Clash.queryGroup(MihomoConfigComposer.GROUP_AWS_EXIT, ProxySort.Default)
                    .proxies.map { it.name }
            }.getOrNull()
            diagnostics.log(Diagnostics.Category.MIHOMO, "chain", "AWS-EXIT members=$members")
        } else {
            diagnostics.log(
                Diagnostics.Category.MIHOMO, "chain",
                "injection not applied — check profile load / patchEasyTier"
            )
        }
    }

    private fun observeStatus() {
        moduleScope.launch {
            manager.status.collect { status ->
                // 首次进入可连接状态时校验 Mihomo 侧注入是否真的生效
                // （配置加载失败/被 profile 覆盖时，AWS-EXIT 不会出现在策略组里）
                if (!chainVerified &&
                    (status.state == EasyTierRuntimeManager.State.CHECKING_LIGHTSAIL ||
                        status.state == EasyTierRuntimeManager.State.CONNECTED)
                ) {
                    chainVerified = true
                    verifyMihomoChain()
                }
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
