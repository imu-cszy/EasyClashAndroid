package com.github.kr328.clash.core.easytier

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * collectNetworkInfos() 返回 JSON 的解析模型。
 *
 * ⚠️ 真实结构与 proto 直觉不同（2026-11-18 模拟器实测）：
 *   "my_node_info": {
 *     "virtual_ipv4": { "address": { "addr": 176061972 }, "network_length": 24 },
 *     "listeners": [ { "url": "ring://..." } ],
 *     ...
 *   }
 * 即 IPv4 是 **u32 整数**（address.addr）而非字符串，前缀字段名为 network_length。
 * 之前按 `{address: String, prefix_len: Int}` 建模会直接反序列化失败。
 *
 * 为保证上游字段演进（新增/改名/类型变化）不会让整个状态机崩掉，这里改为
 * **容错式手工解析**：所有字段缺失或类型不符时退化为 null/默认值，绝不抛异常。
 *
 * proto 定义见 third_party/EasyTier/easytier/src/proto/{api_manage,api_instance,common}.proto
 */
data class NetworkInstanceRunningInfoMap(
    val map: Map<String, NetworkInstanceRunningInfo> = emptyMap(),
)

data class NetworkInstanceRunningInfo(
    val devName: String = "",
    val myNodeInfo: MyNodeInfo? = null,
    val events: List<String> = emptyList(),
    val routes: List<Route> = emptyList(),
    val peers: List<PeerInfo> = emptyList(),
    val peerRoutePairs: List<PeerRoutePair> = emptyList(),
    val running: Boolean = false,
    val errorMsg: String? = null,
) {
    /** 本机虚拟 IPv4，已格式化为 "10.126.126.20/24" */
    val virtualIpv4: String?
        get() = myNodeInfo?.virtualIpv4
}

data class MyNodeInfo(
    val virtualIpv4: String? = null,
    val hostname: String = "",
    val version: String = "",
    val peerId: Long = 0,
)

data class PeerRoutePair(
    val route: Route? = null,
    val peer: PeerInfo? = null,
)

data class Route(
    val peerId: Long = 0,
    /** 对端虚拟 IPv4（含前缀），如 "10.126.126.10/24" */
    val ipv4: String? = null,
    val hostname: String = "",
    /** 路径延迟（毫秒），<0 表示未知 */
    val pathLatencyMs: Int = -1,
    val nextHopPeerId: Long = 0,
    val version: String = "",
)

data class PeerInfo(
    val peerId: Long = 0,
    val conns: List<PeerConnInfo> = emptyList(),
    val defaultConnId: String? = null,
)

data class PeerConnInfo(
    val connId: String = "",
    /** p2p / relay 等 */
    val tunnelType: String? = null,
    val remoteAddr: String? = null,
    val stats: ConnStats? = null,
    val lossRate: Float = 0f,
    val isClosed: Boolean = false,
) {
    /** 是否 P2P 直连；无 tunnel 信息时为 null */
    val isP2p: Boolean?
        get() = tunnelType?.let { it.equals("p2p", ignoreCase = true) }
}

data class ConnStats(
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val latencyUs: Long = 0,
)

object EasyTierJson {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 解析 {"map": {instName: {...}}}。任何结构异常都退化为空 map，不抛异常。
     */
    fun parseRunningInfoMap(raw: String): NetworkInstanceRunningInfoMap {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return NetworkInstanceRunningInfoMap()
        val mapObj = root.obj("map") ?: return NetworkInstanceRunningInfoMap()
        val result = LinkedHashMap<String, NetworkInstanceRunningInfo>()
        for ((name, value) in mapObj) {
            val info = value as? JsonObject ?: continue
            result[name] = parseInfo(info)
        }
        return NetworkInstanceRunningInfoMap(result)
    }

    private fun parseInfo(o: JsonObject): NetworkInstanceRunningInfo =
        NetworkInstanceRunningInfo(
            devName = o.str("dev_name").orEmpty(),
            myNodeInfo = o.obj("my_node_info")?.let(::parseNodeInfo),
            events = o.arr("events").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
            routes = o.arr("routes").orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseRoute) },
            peers = o.arr("peers").orEmpty().mapNotNull { (it as? JsonObject)?.let(::parsePeer) },
            peerRoutePairs = o.arr("peer_route_pairs").orEmpty().mapNotNull { el ->
                (el as? JsonObject)?.let { pair ->
                    PeerRoutePair(
                        route = pair.obj("route")?.let(::parseRoute),
                        peer = pair.obj("peer")?.let(::parsePeer),
                    )
                }
            },
            running = o.bool("running") ?: false,
            errorMsg = o.str("error_msg"),
        )

    private fun parseNodeInfo(o: JsonObject): MyNodeInfo =
        MyNodeInfo(
            virtualIpv4 = ipv4Inet(o.obj("virtual_ipv4")),
            hostname = o.str("hostname").orEmpty(),
            version = o.str("version").orEmpty(),
            peerId = o.long("peer_id") ?: 0,
        )

    private fun parseRoute(o: JsonObject): Route =
        Route(
            peerId = o.long("peer_id") ?: 0,
            ipv4 = ipv4Inet(o.obj("ipv4_addr")),
            hostname = o.str("hostname").orEmpty(),
            pathLatencyMs = (o.long("path_latency") ?: -1L).toInt(),
            nextHopPeerId = o.long("next_hop_peer_id") ?: 0,
            version = o.str("version").orEmpty(),
        )

    private fun parsePeer(o: JsonObject): PeerInfo =
        PeerInfo(
            peerId = o.long("peer_id") ?: 0,
            conns = o.arr("conns").orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseConn) },
            defaultConnId = o.str("default_conn_id"),
        )

    private fun parseConn(o: JsonObject): PeerConnInfo {
        val tunnel = o.obj("tunnel")
        return PeerConnInfo(
            connId = o.str("conn_id").orEmpty(),
            tunnelType = tunnel?.str("tunnel_type"),
            remoteAddr = tunnel?.let { urlField(it, "remote_addr") },
            stats = o.obj("stats")?.let { s ->
                ConnStats(
                    rxBytes = s.long("rx_bytes") ?: 0,
                    txBytes = s.long("tx_bytes") ?: 0,
                    latencyUs = s.long("latency_us") ?: 0,
                )
            },
            lossRate = (o.dbl("loss_rate") ?: 0.0).toFloat(),
            isClosed = o.bool("is_closed") ?: false,
        )
    }

    /** common.Url 序列化为 {"url": "..."}；也兼容直接是字符串的情况 */
    private fun urlField(o: JsonObject, key: String): String? =
        o.str(key) ?: o.obj(key)?.str("url")

    /** {"address": {"addr": <u32>}, "network_length": <n>} -> "10.126.126.20/24" */
    private fun ipv4Inet(o: JsonObject?): String? {
        if (o == null) return null
        val addr = o.obj("address")?.long("addr") ?: o.long("addr") ?: return null
        val ip = u32ToIpv4(addr) ?: return null
        val prefix = o.long("network_length") ?: o.long("prefix_len")
        return if (prefix != null) "$ip/$prefix" else ip
    }

    private fun u32ToIpv4(v: Long): String? {
        if (v < 0 || v > 0xFFFFFFFFL) return null
        return "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.str(key: String): String? {
        val p = this[key] as? JsonPrimitive ?: return null
        if (p is kotlinx.serialization.json.JsonNull) return null
        return p.content.takeIf { p.isString || it != "null" }
    }

    private fun JsonObject.long(key: String): Long? {
        val p = this[key] as? JsonPrimitive ?: return null
        return p.longOrNull ?: p.content.toLongOrNull()
    }

    private fun JsonObject.dbl(key: String): Double? {
        val p = this[key] as? JsonPrimitive ?: return null
        return p.doubleOrNull ?: p.content.toDoubleOrNull()
    }

    private fun JsonObject.bool(key: String): Boolean? {
        val p = this[key] as? JsonPrimitive ?: return null
        return p.booleanOrNull
    }
}
