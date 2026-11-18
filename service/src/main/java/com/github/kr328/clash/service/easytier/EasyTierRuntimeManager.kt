package com.github.kr328.clash.service.easytier

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.easytier.EasyTierException
import com.github.kr328.clash.core.easytier.EasyTierNativeBridge
import com.github.kr328.clash.core.easytier.NetworkInstanceRunningInfo
import com.github.kr328.clash.core.easytier.PeerRoutePair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * EasyTier 运行时管理器：状态机 + 轮询 + 有限退避重连（docs/ARCHITECTURE.md §3.2）。
 *
 * 生命周期：属 :background 进程的 Service 所有；[stop] 由用户触发，此后不再自动重连。
 */
class EasyTierRuntimeManager(
    private val scope: CoroutineScope,
    private val bridge: EasyTierNativeBridge,
    private val configProvider: () -> EasyTierConfig,
    private val healthChecker: LocalSocksHealthChecker,
    val diagnostics: Diagnostics,
) {
    enum class State {
        STOPPED, STARTING, WAITING_FOR_PEER, CHECKING_LOCAL_SOCKS, CHECKING_LIGHTSAIL,
        CONNECTED, DEGRADED, RECONNECTING, FAILED,
    }

    data class PeerSummary(
        val hostname: String,
        val ipv4: String?,
        val latencyUs: Long?,
        val p2p: Boolean?,
        val rxBytes: Long,
        val txBytes: Long,
    )

    data class Status(
        val state: State,
        val since: Long = System.currentTimeMillis(),
        val virtualIpv4: String? = null,
        val peers: List<PeerSummary> = emptyList(),
        val socks5Ok: Boolean? = null,
        val lightsailNormal: LocalSocksHealthChecker.CheckResult? = null,
        val lightsailWarp: LocalSocksHealthChecker.CheckResult? = null,
        val lastError: String? = null,
        val reconnects: Int = 0,
    )

    private val _status = MutableStateFlow(Status(State.STOPPED))
    val status: StateFlow<Status> = _status.asStateFlow()

    private var monitorJob: Job? = null
    private val running = AtomicBoolean(false)

    /** 用户主动停止标志：true 时任何错误/网络事件都不触发自动重连 */
    @Volatile
    private var userStopped = true

    /**
     * 启动 EasyTier 实例并进入监控循环。幂等。
     * 返回 true 表示本地 SOCKS5 已就绪（可放行 Mihomo），false 表示启动失败。
     */
    suspend fun start(): Boolean {
        if (!running.compareAndSet(false, true)) {
            return _status.value.state == State.CONNECTED || _status.value.state == State.CHECKING_LIGHTSAIL
        }
        userStopped = false
        val config = configProvider()
        val errors = config.validate()
        if (errors.isNotEmpty()) {
            diagnostics.log(Diagnostics.Category.CONFIG, "validate", "invalid config: ${errors.joinToString("; ")}")
            transition(State.FAILED) { it.copy(lastError = errors.joinToString("; ")) }
            running.set(false)
            return false
        }

        transition(State.STARTING)
        val toml = config.toToml()
        diagnostics.log(Diagnostics.Category.CONFIG, "toml", config.toMaskedToml())
        try {
            bridge.parseConfig(toml)
            bridge.runNetworkInstance(toml)
            diagnostics.log(Diagnostics.Category.NATIVE, "run", "instance started: ${config.instanceName}")
        } catch (e: EasyTierException) {
            diagnostics.log(Diagnostics.Category.NATIVE, "run", "failed: ${e.message}")
            Log.w("EasyTier start failed: ${e.message}")
            transition(State.FAILED) { it.copy(lastError = e.message) }
            running.set(false)
            return false
        }

        // 等待入网 + 本地 SOCKS 就绪（放行 Mihomo 的门槛）
        val ready = withTimeoutOrNull(PEER_WAIT_TIMEOUT_MS) { waitForLocalSocks(config) } ?: false
        if (!ready) {
            diagnostics.log(Diagnostics.Category.RUNTIME, "wait", "timed out waiting for peers/local socks")
            // 不算彻底失败：进入监控循环重试（DEGRADED 语义）
            transition(State.RECONNECTING) { it.copy(reconnects = it.reconnects + 1) }
        }
        monitorJob = scope.launch { monitorLoop(config) }
        return ready
    }

    /**
     * 停止实例与监控。用户触发；幂等。
     */
    suspend fun stop() {
        userStopped = true
        monitorJob?.cancel()
        monitorJob = null
        withContext(NonCancellable) {
            try {
                bridge.stopAll()
                diagnostics.log(Diagnostics.Category.NATIVE, "stop", "all instances stopped")
            } catch (e: EasyTierException) {
                diagnostics.log(Diagnostics.Category.NATIVE, "stop", "failed: ${e.message}")
            }
        }
        running.set(false)
        transition(State.STOPPED) { Status(State.STOPPED) }
    }

    fun release() {
        monitorJob?.cancel()
        monitorJob = null
    }

    // ------------------------------------------------------------------

    private suspend fun waitForLocalSocks(config: EasyTierConfig): Boolean {
        val deadline = System.currentTimeMillis() + PEER_WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val info = currentInfo(config)
            val hasPeer = info?.peers?.isNotEmpty() == true ||
                info?.peerRoutePairs?.isNotEmpty() == true
            transition(if (hasPeer) State.CHECKING_LOCAL_SOCKS else State.WAITING_FOR_PEER) {
                it.copy(
                    virtualIpv4 = info?.virtualIpv4,
                    peers = info.toPeerSummaries(),
                )
            }
            if (hasPeer) {
                val r = healthChecker.checkLocal(port = config.socks5Port)
                diagnostics.log(
                    Diagnostics.Category.SOCKS5, "local",
                    "port=${config.socks5Port} result=${r.javaClass.simpleName}(${(r as? LocalSocksHealthChecker.CheckResult.Failed)?.detail ?: "ok"})"
                )
                if (r is LocalSocksHealthChecker.CheckResult.Ok) return true
            }
            delay(POLL_FAST_MS)
        }
        return false
    }

    private suspend fun monitorLoop(config: EasyTierConfig) {
        var backoffMs = INITIAL_BACKOFF_MS
        while (true) {
            val state = _status.value.state
            val info = try {
                bridge.collectNetworkInfos().map[config.instanceName]
            } catch (e: EasyTierException) {
                diagnostics.log(Diagnostics.Category.NATIVE, "collect", "failed: ${e.message}")
                null
            }
            val hasPeer = info?.peers?.isNotEmpty() == true || info?.peerRoutePairs?.isNotEmpty() == true
            val instanceRunning = info?.running == true
            val socks = healthChecker.checkLocal(port = config.socks5Port)
            val socksOk = socks is LocalSocksHealthChecker.CheckResult.Ok

            if (!instanceRunning || !hasPeer) {
                // 实例未运行或未入网
                if (!userStopped && state != State.RECONNECTING) {
                    diagnostics.log(Diagnostics.Category.RUNTIME, "monitor", "lost peers -> reconnecting")
                    transition(State.RECONNECTING) { it.copy(reconnects = it.reconnects + 1) }
                }
                if (state == State.RECONNECTING) {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                    if (!userStopped) {
                        try {
                            bridge.runNetworkInstance(config.toToml())
                            diagnostics.log(Diagnostics.Category.NATIVE, "reconnect", "instance restarted")
                        } catch (e: EasyTierException) {
                            diagnostics.log(Diagnostics.Category.NATIVE, "reconnect", "failed: ${e.message}")
                        }
                    }
                    continue
                }
            } else if (hasPeer && socksOk) {
                backoffMs = INITIAL_BACKOFF_MS
                if (state != State.CONNECTED && state != State.DEGRADED && state != State.CHECKING_LIGHTSAIL) {
                    transition(State.CHECKING_LIGHTSAIL) { it.copy(virtualIpv4 = info?.virtualIpv4, peers = info.toPeerSummaries(), socks5Ok = true) }
                    checkLightsail(config)
                } else {
                    update { it.copy(virtualIpv4 = info?.virtualIpv4, peers = info.toPeerSummaries(), socks5Ok = true) }
                }
            } else if (hasPeer && !socksOk) {
                if (state == State.CONNECTED || state == State.DEGRADED) {
                    diagnostics.log(Diagnostics.Category.SOCKS5, "monitor", "local socks lost -> degraded")
                }
                transition(State.DEGRADED) { it.copy(socks5Ok = false, virtualIpv4 = info?.virtualIpv4, peers = info.toPeerSummaries()) }
            }

            delay(if (state == State.CONNECTED || state == State.DEGRADED) POLL_SLOW_MS else POLL_FAST_MS)
        }
    }

    private suspend fun checkLightsail(config: EasyTierConfig) {
        val normal = healthChecker.checkThrough(
            viaHost = "127.0.0.1", viaPort = config.socks5Port,
            targetHost = config.lightsailHost, targetPort = config.normalPort,
            username = config.normalUser.takeIf { it.isNotEmpty() },
            password = config.normalPassword.takeIf { it.isNotEmpty() },
        )
        val warp = healthChecker.checkThrough(
            viaHost = "127.0.0.1", viaPort = config.socks5Port,
            targetHost = config.lightsailHost, targetPort = config.warpPort,
            username = config.warpUser.takeIf { it.isNotEmpty() },
            password = config.warpPassword.takeIf { it.isNotEmpty() },
        )
        diagnostics.log(Diagnostics.Category.SOCKS5, "lightsail", "normal=${describe(normal)} warp=${describe(warp)}")
        val okN = normal is LocalSocksHealthChecker.CheckResult.Ok
        val okW = warp is LocalSocksHealthChecker.CheckResult.Ok
        if (okN || okW) {
            transition(State.CONNECTED) {
                it.copy(lightsailNormal = normal, lightsailWarp = warp, lastError = null)
            }
        } else {
            transition(State.DEGRADED) {
                it.copy(
                    lightsailNormal = normal, lightsailWarp = warp,
                    lastError = "lightsail unreachable: ${describe(normal)}; ${describe(warp)}",
                )
            }
        }
    }

    private fun currentInfo(config: EasyTierConfig): NetworkInstanceRunningInfo? = try {
        bridge.collectNetworkInfos().map[config.instanceName]
    } catch (e: EasyTierException) {
        diagnostics.log(Diagnostics.Category.NATIVE, "collect", "failed: ${e.message}")
        null
    }

    private fun NetworkInstanceRunningInfo?.toPeerSummaries(): List<PeerSummary> {
        if (this == null) return emptyList()
        val byPeer = peers.associateBy { it.peerId }
        fun List<PeerRoutePair>.toSummaries() = mapNotNull { pair ->
            val route = pair.route ?: return@mapNotNull null
            val conn = pair.peer?.conns?.firstOrNull { !it.isClosed } ?: pair.peer?.conns?.firstOrNull()
            PeerSummary(
                hostname = route.hostname.ifBlank { pair.peer?.peerId?.toString() ?: "?" },
                ipv4 = route.ipv4Addr?.address,
                latencyUs = conn?.stats?.latencyUs?.takeIf { it > 0 } ?: route.pathLatency.takeIf { it >= 0 }?.let { it * 1000L },
                p2p = conn?.isP2p,
                rxBytes = conn?.stats?.rxBytes ?: 0,
                txBytes = conn?.stats?.txBytes ?: 0,
            )
        }
        val fromPairs = peerRoutePairs.toSummaries()
        if (fromPairs.isNotEmpty()) return fromPairs
        // peer_route_pairs 为空时退回 routes+peers 拼装
        return routes.map { r ->
            val conn = byPeer[r.peerId]?.conns?.firstOrNull()
            PeerSummary(
                hostname = r.hostname.ifBlank { r.peerId.toString() },
                ipv4 = r.ipv4Addr?.address,
                latencyUs = conn?.stats?.latencyUs?.takeIf { it > 0 } ?: r.pathLatency.takeIf { it >= 0 }?.let { it * 1000L },
                p2p = conn?.isP2p,
                rxBytes = conn?.stats?.rxBytes ?: 0,
                txBytes = conn?.stats?.txBytes ?: 0,
            )
        }
    }

    private fun describe(r: LocalSocksHealthChecker.CheckResult): String = when (r) {
        is LocalSocksHealthChecker.CheckResult.Ok -> "ok(${r.elapsedMs}ms)"
        is LocalSocksHealthChecker.CheckResult.Failed -> "failed@${r.stage}:${r.detail}"
    }

    private fun transition(state: State, transform: (Status) -> Status = { it }) {
        _status.value = transform(_status.value.copy(state = state, since = System.currentTimeMillis()))
        Log.d("EasyTier state -> $state")
    }

    private fun update(transform: (Status) -> Status) {
        _status.value = transform(_status.value)
    }

    companion object {
        private const val POLL_FAST_MS = 1_000L
        private const val POLL_SLOW_MS = 5_000L
        private const val PEER_WAIT_TIMEOUT_MS = 30_000L
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 32_000L
    }
}
