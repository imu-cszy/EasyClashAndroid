package com.github.kr328.clash.service.easytier

import com.github.kr328.clash.core.Clash
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Mihomo 代理链注入器（P3，docs/IMPLEMENTATION_PLAN.md §3.7）。
 *
 * 生成 {"proxies":[...],"proxy-groups":[...]} JSON，经 Clash.setEasyTierProxies 注入；
 * Go 侧 patchEasyTier 在用户配置之后合并（去旧追新），保留用户全部 proxies/rules。
 *
 * 防线（生成前断言）：
 *  - 名称固定且互不重复；
 *  - 两个 AWS 出站的 dialer-proxy 只允许指向 ET-LOCAL；
 *  - ET-LOCAL 不进入任何 proxy-group（避免被选为互联网出口/环路）。
 */
object MihomoConfigComposer {
    const val PROXY_ET_LOCAL = "ET-LOCAL"
    const val PROXY_AWS_NORMAL = "AWS-NORMAL"
    const val PROXY_AWS_WARP = "AWS-WARP"
    const val GROUP_AWS_EXIT = "AWS-EXIT"

    private val RESERVED = setOf(PROXY_ET_LOCAL, PROXY_AWS_NORMAL, PROXY_AWS_WARP, GROUP_AWS_EXIT)

    /**
     * 生成注入 JSON。enabled=false 或配置不完整时返回 null（调用方应清除注入）。
     *
     * @param selfUid 本应用 UID 提供者（单元测试可注入固定值；默认读 android.os.Process）
     */
    fun composeOrNull(
        config: EasyTierConfig,
        selfUid: () -> Int = { android.os.Process.myUid() },
    ): String? {
        if (!config.enabled) return null
        if (config.validate().isNotEmpty()) return null

        val etLocal = socks5(
            name = PROXY_ET_LOCAL,
            server = "127.0.0.1",
            port = config.socks5Port,
        )
        val awsNormal = socks5(
            name = PROXY_AWS_NORMAL,
            server = config.lightsailHost,
            port = config.normalPort,
            username = config.normalUser,
            password = config.normalPassword,
            dialerProxy = PROXY_ET_LOCAL,
        )
        val awsWarp = socks5(
            name = PROXY_AWS_WARP,
            server = config.lightsailHost,
            port = config.warpPort,
            username = config.warpUser,
            password = config.warpPassword,
            dialerProxy = PROXY_ET_LOCAL,
        )
        val group = buildJsonObject {
            put("name", GROUP_AWS_EXIT)
            put("type", "select")
            put("proxies", buildJsonArray {
                add(JsonPrimitive(PROXY_AWS_NORMAL))
                add(JsonPrimitive(PROXY_AWS_WARP))
            })
        }

        assertNoLoop(listOf(etLocal, awsNormal, awsWarp), group)

        // 关键：把本应用自身流量置为 DIRECT（最高优先级规则）。
        // EasyTier 的 underlay socket 由本进程创建、未 protect()，一旦 TUN 建立就会被捕获；
        // 若用户把流量路由到 AWS-EXIT，这些 underlay 连接会再次进入 AWS-EXIT → 环路。
        // UID 规则只在 Android 生效（mihomo rules/common/uid.go），且 CMFA 已把
        // querySocketUid 接入 TUN，因此可精确命中本应用。
        val selfUidValue = selfUid()
        val rules = buildJsonArray {
            add(JsonPrimitive("UID,$selfUidValue,DIRECT"))
        }

        return JsonObject(
            mapOf(
                "proxies" to JsonArray(listOf(etLocal, awsNormal, awsWarp)),
                "proxy-groups" to JsonArray(listOf(group)),
                "rules" to rules,
                // 把用户配置里所有指向 overlay 网段的代理自动接到 ET-LOCAL。
                // 这些代理在 Windows 上可直接连（EasyTier 是系统 TUN），但在 Android 上
                // EasyTier 为 No-TUN，只有经 127.0.0.1:11080 才能进入 overlay；
                // 不自动改写的话，用户原有 profile 的节点在手机上全部不可用。
                "overlayDialer" to buildJsonObject {
                    put("proxy", PROXY_ET_LOCAL)
                    put("cidr", overlayCidr(config.ipv4))
                },
            )
        ).toString()
    }

    /**
     * 由虚拟 IP 推导 overlay 网段，如 "10.126.126.20/24" -> "10.126.126.0/24"。
     * 无法解析时返回 null（此时不做自动改写）。
     */
    fun overlayCidr(ipv4: String): String? {
        val text = ipv4.trim()
        val addrPart = text.substringBefore('/')
        val prefix = text.substringAfter('/', "24").toIntOrNull() ?: 24
        if (prefix !in 0..32) return null
        val octets = addrPart.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return null
        val value = (octets[0].toLong() shl 24) or (octets[1].toLong() shl 16) or
            (octets[2].toLong() shl 8) or octets[3].toLong()
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val network = value and mask
        return "${(network ushr 24) and 0xFF}.${(network ushr 16) and 0xFF}." +
            "${(network ushr 8) and 0xFF}.${network and 0xFF}/$prefix"
    }

    /**
     * 应用到内核（session 注入；禁用时清除）。凭据只进入内存，不落盘、不日志。
     */
    fun apply(config: EasyTierConfig) {
        val json = composeOrNull(config)
        Clash.setEasyTierProxies(json)
    }

    private fun socks5(
        name: String,
        server: String,
        port: Int,
        username: String = "",
        password: String = "",
        dialerProxy: String? = null,
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("type", "socks5")
        put("server", server)
        put("port", port)
        if (username.isNotEmpty()) put("username", username)
        if (password.isNotEmpty()) put("password", password)
        if (dialerProxy != null) put("dialer-proxy", dialerProxy)
        put("udp", true)
    }

    private fun assertNoLoop(proxies: List<JsonObject>, group: JsonObject) {
        val names = proxies.mapNotNull { (it["name"] as? JsonPrimitive)?.content }.toSet()
        require(names.size == proxies.size && names == RESERVED - GROUP_AWS_EXIT) {
            "reserved proxy names mismatch: $names"
        }
        proxies.forEach { p ->
            val dp = (p["dialer-proxy"] as? JsonPrimitive)?.content
            require(dp == null || dp == PROXY_ET_LOCAL) { "dialer-proxy must be $PROXY_ET_LOCAL, got $dp" }
        }
        val groupProxies = (group["proxies"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            .orEmpty()
        require(PROXY_ET_LOCAL !in groupProxies) { "ET-LOCAL must not appear in any proxy-group" }
        require(groupProxies.all { it in names }) { "group references unknown proxy" }
    }
}
