package com.github.kr328.clash.core.easytier

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * collectNetworkInfos() 返回 JSON 的反序列化模型。
 *
 * 字段名与 EasyTier v2.6.4 prost+serde 序列化输出一致（Rust 字段名，snake_case）。
 * proto 定义见 third_party/EasyTier/easytier/src/proto/{api_manage,api_instance,common}.proto。
 * 所有字段给默认值：native 侧字段增删不致崩溃。
 */
object EasyTierJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
}

@Serializable
data class NetworkInstanceRunningInfoMap(
    val map: Map<String, NetworkInstanceRunningInfo> = emptyMap(),
)

@Serializable
data class NetworkInstanceRunningInfo(
    @SerialName("dev_name")
    val devName: String = "",
    @SerialName("my_node_info")
    val myNodeInfo: MyNodeInfo? = null,
    val events: List<String> = emptyList(),
    val routes: List<Route> = emptyList(),
    val peers: List<PeerInfo> = emptyList(),
    @SerialName("peer_route_pairs")
    val peerRoutePairs: List<PeerRoutePair> = emptyList(),
    val running: Boolean = false,
    @SerialName("error_msg")
    val errorMsg: String? = null,
) {
    /** 本机虚拟 IPv4（如 10.126.126.20） */
    val virtualIpv4: String?
        get() = myNodeInfo?.virtualIpv4?.let { "${it.address}/${it.prefixLen}" }
}

@Serializable
data class MyNodeInfo(
    @SerialName("virtual_ipv4")
    val virtualIpv4: InetPair? = null,
    val hostname: String = "",
    val version: String = "",
    @SerialName("peer_id")
    val peerId: Long = 0,
)

/** proto common.Ipv4Inet */
@Serializable
data class InetPair(
    val address: String = "",
    @SerialName("prefix_len")
    val prefixLen: Int = 0,
)

@Serializable
data class PeerRoutePair(
    val route: Route? = null,
    val peer: PeerInfo? = null,
)

/** proto api.instance.Route */
@Serializable
data class Route(
    @SerialName("peer_id")
    val peerId: Long = 0,
    @SerialName("ipv4_addr")
    val ipv4Addr: InetPair? = null,
    val hostname: String = "",
    @SerialName("path_latency")
    val pathLatency: Int = -1,
    @SerialName("next_hop_peer_id")
    val nextHopPeerId: Long = 0,
    val version: String = "",
)

@Serializable
data class PeerInfo(
    @SerialName("peer_id")
    val peerId: Long = 0,
    val conns: List<PeerConnInfo> = emptyList(),
    @SerialName("default_conn_id")
    val defaultConnId: String? = null,
)

@Serializable
data class PeerConnInfo(
    @SerialName("conn_id")
    val connId: String = "",
    val tunnel: TunnelInfo? = null,
    val stats: ConnStats? = null,
    @SerialName("loss_rate")
    val lossRate: Float = 0f,
    @SerialName("is_closed")
    val isClosed: Boolean = false,
) {
    /** 是否 P2P 直连（否则为 relay）；无连接信息时为 null */
    val isP2p: Boolean?
        get() = tunnel?.tunnelType?.let { it.equals("p2p", ignoreCase = true) }
}

/** proto common.TunnelInfo：tunnel_type 如 p2p / relay */
@Serializable
data class TunnelInfo(
    @SerialName("tunnel_type")
    val tunnelType: String = "",
    @SerialName("remote_addr")
    val remoteAddr: String? = null,
)

@Serializable
data class ConnStats(
    @SerialName("rx_bytes")
    val rxBytes: Long = 0,
    @SerialName("tx_bytes")
    val txBytes: Long = 0,
    @SerialName("latency_us")
    val latencyUs: Long = 0,
)
