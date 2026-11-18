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
     */
    fun composeOrNull(config: EasyTierConfig): String? {
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
        return JsonObject(
            mapOf(
                "proxies" to JsonArray(listOf(etLocal, awsNormal, awsWarp)),
                "proxy-groups" to JsonArray(listOf(group)),
            )
        ).toString()
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
