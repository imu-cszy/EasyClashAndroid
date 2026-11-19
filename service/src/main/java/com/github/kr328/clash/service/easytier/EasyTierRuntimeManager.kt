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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** 串行化原生调用：保证 stop() 不会与 start() 的 run/parse 竞争 */
    private val nativeLock = Mutex()

    /** 已写入诊断的 EasyTier 事件（去重，避免刷屏） */
    private val loggedEvents = LinkedHashSet<String>()

    /** 虚拟 IP 冲突只告警一次 */
    private var conflictLogged = false

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
        when (val load = bridge.ensureLoaded()) {
            is EasyTierNativeBridge.LoadResult.Failed -> {
                diagnostics.log(Diagnostics.Category.NATIVE, "load", "failed: ${load.error}")
                transition(State.FAILED) { it.copy(lastError = load.error) }
                running.set(false)
                return false
            }

            EasyTierNativeBridge.LoadResult.Ok ->
                diagnostics.log(
                    Diagnostics.Category.NATIVE, "load",
                    EasyTierNativeBridge.loadDiagnostics ?: "already loaded"
                )
        }
        val toml = config.toToml()
        diagnostics.log(Diagnostics.Category.CONFIG, "toml", config.toMaskedToml())
        // 原生调用加锁：避免 stop() 与 start() 竞争（服务启动途中被停止时，
        // stopAll 可能先于 runNetworkInstance 执行，导致实例泄漏、端口不释放）。
        nativeLock.withLock {
            if (userStopped) return false
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
            if (userStopped) {
                // stop() 已请求：不要留下实例
                runCatching { bridge.stopAll() }
                diagnostics.log(Diagnostics.Category.NATIVE, "stop", "instance stopped during startup")
                running.set(false)
                transition(State.STOPPED) { Status(State.STOPPED) }
                return false
            }
        }

        // 等待「实例运行 + 本地 SOCKS5 监听」（放行 Mihomo 的门槛；peer 由监控循环继续跟进）
        val ready = withTimeoutOrNull(PEER_WAIT_TIMEOUT_MS) { waitForLocalSocks(config) } ?: false
        if (!ready) {
            diagnostics.log(Diagnostics.Category.RUNTIME, "wait", "timed out waiting for instance/local socks")
            transition(State.RECONNECTING) { it.copy(reconnects = it.reconnects + 1) }
        }
        if (userStopped) {
            return false
        }
        monitorJob = scope.launch { monitorLoop(config) }
        return ready
    }

    /**
     * 停止实例与监控。用户触发；幂等。
     *
     * 先置 userStopped 并取消监控，再在 nativeLock 内 stopAll——保证与 start() 串行，
     * 不会出现「stop 先执行、start 后创建实例」的泄漏。
     */
    suspend fun stop() {
        userStopped = true
        monitorJob?.cancel()
        monitorJob = null
        withContext(NonCancellable) {
            nativeLock.withLock {
                try {
                    bridge.stopAll()
                    diagnostics.log(Diagnostics.Category.NATIVE, "stop", "all instances stopped")
                } catch (e: EasyTierException) {
                    diagnostics.log(Diagnostics.Category.NATIVE, "stop", "failed: ${e.message}")
                }
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
            if (userStopped) return false
            val info = currentInfo(config)
            val instanceRunning = info?.running == true
            val socks = healthChecker.checkLocal(port = config.socks5Port)
            val socksOk = socks is LocalSocksHealthChecker.CheckResult.Ok
            transition(if (socksOk) State.CHECKING_LOCAL_SOCKS else State.WAITING_FOR_PEER) {
                it.copy(
                    virtualIpv4 = info?.virtualIpv4,
                    peers = info.toPeerSummaries(),
                    socks5Ok = socksOk,
                )
            }
            diagnostics.log(
                Diagnostics.Category.SOCKS5, "local",
                "port=${config.socks5Port} listening=$socksOk running=$instanceRunning"
            )
            // 门槛：实例已运行 + 本地 SOCKS5 监听（peer 由监控循环继续跟进）
            if (instanceRunning && socksOk) return true
            delay(POLL_FAST_MS)
        }
        return false
    }

    /**
     * 监控循环。
     *
     * 设计要点（2026-11-18 真机教训）：
     * - **只有在实例确实不存在/未运行时才重启**。peer 列表短暂为空是正常的（刚启动、
     *   OSPF 路由尚未收敛），此前把"无 peer"当失败会导致每秒重启实例、隧道反复中断。
     * - EasyTier 自身会维护重连；我们重启只会破坏它。
     * - 出口可用性以「本地 SOCKS5 + 隧道内二级 SOCKS5 探测」为准（应用层验收），
     *   peer 列表仅用于展示与状态细分。
     * - EasyTier 的 events（含 IP 冲突、连接错误等）增量写入诊断日志。
     */
    private suspend fun monitorLoop(config: EasyTierConfig) {
        var backoffMs = INITIAL_BACKOFF_MS
        var lastExitCheckAt = 0L
        while (true) {
            val info = try {
                bridge.collectNetworkInfos().map[config.instanceName]
            } catch (e: EasyTierException) {
                diagnostics.log(Diagnostics.Category.NATIVE, "collect", "failed: ${e.message}")
                null
            }
            logNewEvents(info)
            checkIpConflict(info)

            val instanceRunning = info?.running == true
            val hasPeer = info?.peers?.isNotEmpty() == true || info?.peerRoutePairs?.isNotEmpty() == true
            val socks = healthChecker.checkLocal(port = config.socks5Port)
            val socksOk = socks is LocalSocksHealthChecker.CheckResult.Ok

            if (!instanceRunning) {
                if (userStopped) {
                    delay(POLL_SLOW_MS)
                    continue
                }
                // 唯一需要重启的情形：实例不存在（进程内实例被回收/启动失败）
                diagnostics.log(
                    Diagnostics.Category.RUNTIME, "monitor",
                    "instance missing -> restart in ${backoffMs}ms (reconnects=${_status.value.reconnects + 1})"
                )
                transition(State.RECONNECTING) { it.copy(reconnects = it.reconnects + 1) }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                nativeLock.withLock {
                    if (!userStopped) {
                        try {
                            bridge.runNetworkInstance(config.toToml())
                            diagnostics.log(Diagnostics.Category.NATIVE, "reconnect", "instance restarted")
                        } catch (e: EasyTierException) {
                            diagnostics.log(Diagnostics.Category.NATIVE, "reconnect", "failed: ${e.message}")
                        }
                    }
                }
                continue
            }
            backoffMs = INITIAL_BACKOFF_MS

            if (!socksOk) {
                transition(State.DEGRADED) {
                    it.copy(
                        socks5Ok = false,
                        virtualIpv4 = info?.virtualIpv4,
                        peers = info.toPeerSummaries(),
                        lastError = "local SOCKS5 not listening on ${config.socks5Port}",
                    )
                }
                delay(POLL_FAST_MS)
                continue
            }

            // 本地 SOCKS 正常：按间隔做出口端到端检测（非 CONNECTED 时更快重试）
            val now = System.currentTimeMillis()
            val interval = if (_status.value.state == State.CONNECTED) EXIT_CHECK_INTERVAL_MS else EXIT_RETRY_MS
            if (now - lastExitCheckAt >= interval) {
                lastExitCheckAt = now
                transition(State.CHECKING_LIGHTSAIL) {
                    it.copy(socks5Ok = true, virtualIpv4 = info?.virtualIpv4, peers = info.toPeerSummaries())
                }
                checkLightsail(config, hasPeer)
            } else {
                update {
                    it.copy(socks5Ok = true, virtualIpv4 = info?.virtualIpv4, peers = info.toPeerSummaries())
                }
            }

            delay(POLL_SLOW_MS)
        }
    }

    /** EasyTier 事件增量落日志（用于发现 IP 冲突、握手失败等） */
    private fun logNewEvents(info: NetworkInstanceRunningInfo?) {
        val events = info?.events ?: return
        for (e in events.takeLast(4)) {
            if (loggedEvents.add(e)) {
                diagnostics.log(Diagnostics.Category.RUNTIME, "event", e.take(400))
            }
        }
        if (loggedEvents.size > 300) {
            val iterator = loggedEvents.iterator()
            repeat(loggedEvents.size - 200) {
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
    }

    /** 检测虚拟 IP 冲突：有其它节点声明与本机相同的 IP（会导致 relay / 路由错乱） */
    private fun checkIpConflict(info: NetworkInstanceRunningInfo?) {
        val myIp = info?.myNodeInfo?.virtualIpv4?.substringBefore('/') ?: return
        val peerIps = info.peerRoutePairs.mapNotNull { it.route?.ipv4?.substringBefore('/') } +
            info.routes.mapNotNull { it.ipv4?.substringBefore('/') }
        if (myIp in peerIps && !conflictLogged) {
            conflictLogged = true
            diagnostics.log(
                Diagnostics.Category.RUNTIME, "conflict",
                "another node advertises the same virtual IP $myIp — stop the other EasyTier client or change ipv4"
            )
        }
    }

    private suspend fun checkLightsail(config: EasyTierConfig, hasPeer: Boolean) {
        // 端到端：本地 SOCKS5 → 隧道 → Lightsail SOCKS5 协商/认证 → 探测目标。
        // 这样凭据错误会落在 REMOTE_AUTH 阶段，而不是笼统的"不可达"。
        val normal = healthChecker.checkRemoteThrough(
            viaHost = "127.0.0.1", viaPort = config.socks5Port,
            targetHost = config.lightsailHost, targetPort = config.normalPort,
            username = config.normalUser.takeIf { it.isNotEmpty() },
            password = config.normalPassword.takeIf { it.isNotEmpty() },
        )
        val warp = healthChecker.checkRemoteThrough(
            viaHost = "127.0.0.1", viaPort = config.socks5Port,
            targetHost = config.lightsailHost, targetPort = config.warpPort,
            username = config.warpUser.takeIf { it.isNotEmpty() },
            password = config.warpPassword.takeIf { it.isNotEmpty() },
        )
        diagnostics.log(Diagnostics.Category.SOCKS5, "lightsail", "normal=${describe(normal)} warp=${describe(warp)}")
        val okN = normal is LocalSocksHealthChecker.CheckResult.Ok
        val okW = warp is LocalSocksHealthChecker.CheckResult.Ok

        // 出口可用时再取一次公网 IP：证明出口能发出真实应用层流量（比 CONNECT 更强）
        if (okN) {
            val ip = healthChecker.fetchEgressIp(
                viaHost = "127.0.0.1", viaPort = config.socks5Port,
                targetHost = config.lightsailHost, targetPort = config.normalPort,
                username = config.normalUser.takeIf { it.isNotEmpty() },
                password = config.normalPassword.takeIf { it.isNotEmpty() },
            )
            diagnostics.log(Diagnostics.Category.SOCKS5, "egress", "normal public IP=${ip ?: "unknown"}")
        }
        if (okW) {
            val ip = healthChecker.fetchEgressIp(
                viaHost = "127.0.0.1", viaPort = config.socks5Port,
                targetHost = config.lightsailHost, targetPort = config.warpPort,
                username = config.warpUser.takeIf { it.isNotEmpty() },
                password = config.warpPassword.takeIf { it.isNotEmpty() },
            )
            diagnostics.log(Diagnostics.Category.SOCKS5, "egress", "warp public IP=${ip ?: "unknown"}")
        }

        if (okN || okW) {
            transition(State.CONNECTED) {
                it.copy(lightsailNormal = normal, lightsailWarp = warp, lastError = null)
            }
        } else {
            // 出口都不可用：区分「还没连上 peer」与「连上了但出口故障」
            transition(if (!hasPeer) State.WAITING_FOR_PEER else State.DEGRADED) {
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
                ipv4 = route.ipv4,
                latencyUs = conn?.stats?.latencyUs?.takeIf { it > 0 } ?: route.pathLatencyMs.takeIf { it >= 0 }?.let { it * 1000L },
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
                ipv4 = r.ipv4,
                latencyUs = conn?.stats?.latencyUs?.takeIf { it > 0 } ?: r.pathLatencyMs.takeIf { it >= 0 }?.let { it * 1000L },
                p2p = conn?.isP2p,
                rxBytes = conn?.stats?.rxBytes ?: 0,
                txBytes = conn?.stats?.txBytes ?: 0,
            )
        }
    }

    private fun describe(r: LocalSocksHealthChecker.CheckResult): String = when (r) {
        is LocalSocksHealthChecker.CheckResult.Ok -> "ok(${r.elapsedMs}ms)"
        is LocalSocksHealthChecker.CheckResult.Failed -> "failed@${r.stage}(${r.elapsedMs}ms):${r.detail}"
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

        /** CONNECTED 后出口复检间隔 */
        private const val EXIT_CHECK_INTERVAL_MS = 30_000L

        /** 未 CONNECTED 时出口重试间隔 */
        private const val EXIT_RETRY_MS = 10_000L
    }
}
