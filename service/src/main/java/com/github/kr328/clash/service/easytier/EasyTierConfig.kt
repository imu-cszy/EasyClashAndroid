package com.github.kr328.clash.service.easytier

import java.net.InetAddress

/**
 * EasyTier 集成配置（业务字段，不含 mihomo 侧凭据加密存储——见 SecretStore）。
 *
 * TOML 字段与 EasyTier v2.6.4 `easytier/src/common/config.rs` 的 Config 结构体一致。
 * 注意：上游 contrib 的 example_config.toml 字段名（inst_name/network/peers）是错的，勿参考。
 */
data class EasyTierConfig(
    val enabled: Boolean = false,
    val instanceName: String = DEFAULT_INSTANCE_NAME,
    val networkName: String = "",
    val networkSecret: String = "",      // 内存态；持久化走 SecretStore 加密
    val hostname: String = DEFAULT_INSTANCE_NAME,
    val ipv4: String = DEFAULT_IPV4,
    val peers: List<String> = emptyList(),
    val socks5Port: Int = DEFAULT_SOCKS5_PORT,
    val defaultProtocol: String = "tcp",
    val latencyFirst: Boolean = false,
    val enableEncryption: Boolean = true,
    val lightsailHost: String = DEFAULT_LIGHTSAIL_HOST,
    val normalPort: Int = 1080,
    val warpPort: Int = 1081,
    val normalUser: String = "",
    val warpUser: String = "",
    val normalPassword: String = "",     // 内存态；持久化走 SecretStore
    val warpPassword: String = "",       // 内存态；持久化走 SecretStore
) {
    companion object {
        const val DEFAULT_INSTANCE_NAME = "easyclash-android"
        const val DEFAULT_IPV4 = "10.126.126.20/24"
        const val DEFAULT_SOCKS5_PORT = 11080
        const val DEFAULT_LIGHTSAIL_HOST = "10.126.126.10"

        // uri = "scheme://host[:port][/...]"（host 打码，保留端口）
        private val PEER_URI_LINE = Regex("""(uri\s*=\s*")([a-zA-Z][a-zA-Z0-9+.\-]*)://([^"]+)(")""")
    }

    /**
     * 返回校验错误清单（空 = 可用）。UI 直接展示。
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (networkName.isBlank()) errors += "network name is required"
        if (ipv4.isBlank()) {
            errors += "virtual IPv4 is required"
        } else {
            parseIpv4Cidr(ipv4)?.let { (ip, prefix) ->
                if (prefix !in 8..32) errors += "IPv4 prefix length must be in 8..32"
            } ?: run { errors += "invalid IPv4/CIDR: $ipv4" }
        }
        if (peers.isEmpty()) errors += "at least one peer URI is required"
        peers.forEachIndexed { i, p ->
            if (!p.contains("://")) errors += "peer #${i + 1} must be a full URI (scheme://host:port)"
        }
        if (socks5Port !in 1024..65535) errors += "SOCKS5 port must be in 1024..65535"
        if (lightsailHost.isBlank()) errors += "Lightsail host is required"
        if (normalPort !in 1..65535) errors += "normal port must be in 1..65535"
        if (warpPort !in 1..65535) errors += "WARP port must be in 1..65535"
        return errors
    }

    /**
     * 生成传给 runNetworkInstance 的 TOML（No-TUN + 仅回环 SOCKS5）。
     */
    fun toToml(): String = buildString {
        appendLine("instance_name = \"${escape(instanceName)}\"")
        appendLine("hostname = \"${escape(hostname)}\"")
        appendLine("ipv4 = \"${parseIpv4Cidr(ipv4)?.let { (ip, prefix) -> "$ip/$prefix" } ?: escape(ipv4)}\"")
        appendLine()
        appendLine("[network_identity]")
        appendLine("network_name = \"${escape(networkName)}\"")
        if (networkSecret.isNotEmpty()) {
            appendLine("network_secret = \"${escape(networkSecret)}\"")
        }
        appendLine()
        peers.forEach { peer ->
            appendLine("[[peer]]")
            appendLine("uri = \"${escape(peer)}\"")
        }
        if (peers.isNotEmpty()) appendLine()
        appendLine("socks5_proxy = \"socks5://127.0.0.1:$socks5Port\"")
        appendLine()
        appendLine("[flags]")
        appendLine("no_tun = true")
        appendLine("default_protocol = \"${escape(defaultProtocol)}\"")
        appendLine("latency_first = $latencyFirst")
        appendLine("enable_encryption = $enableEncryption")
    }

    /**
     * 脱敏版 TOML：日志/诊断导出专用。secret 打码；peer URI 仅保留 scheme 与端口。
     */
    fun toMaskedToml(): String = toToml()
        .replace(Regex("network_secret = \"[^\"]*\""), "network_secret = \"***\"")
        .replace(PEER_URI_LINE) { m ->
            val hostPort = m.groupValues[3]
            val portPart = hostPort.indexOf(':').takeIf { it >= 0 }?.let { hostPort.substring(it) } ?: ""
            "${m.groupValues[1]}${m.groupValues[2]}://***$portPart${m.groupValues[4]}"
        }

    /** "10.126.126.20/24" -> ("10.126.126.20", 24)；"10.126.126.20" -> (ip, 24)；非法 -> null */
    fun parseIpv4Cidr(text: String): Pair<String, Int>? {
        val trimmed = text.trim()
        val addr: String
        val prefix: Int
        if (trimmed.contains('/')) {
            val parts = trimmed.split('/', limit = 2)
            addr = parts[0]
            prefix = parts[1].toIntOrNull() ?: return null
        } else {
            addr = trimmed
            prefix = 24
        }
        val ia = try {
            InetAddress.getByName(addr)
        } catch (e: Exception) {
            return null
        }
        if (ia.address.size != 4) return null
        return addr to prefix
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
