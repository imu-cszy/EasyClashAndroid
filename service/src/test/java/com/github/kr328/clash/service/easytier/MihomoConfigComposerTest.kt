package com.github.kr328.clash.service.easytier

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MihomoConfigComposerTest {
    private fun validConfig() = EasyTierConfig(
        enabled = true,
        networkName = "net",
        ipv4 = "10.126.126.20/24",
        peers = listOf("tcp://p:11010"),
        socks5Port = 11080,
        lightsailHost = "10.126.126.10",
        normalPort = 1080,
        warpPort = 1081,
        normalUser = "u1",
        normalPassword = "p1",
        warpUser = "u2",
        warpPassword = "p2",
    )

    @Test
    fun `compose builds three proxies and one select group`() {
        val composed = MihomoConfigComposer.composeOrNull(validConfig())
        assertNotNull(composed)
        val json = Json.parseToJsonElement(composed!!).jsonObject
        val proxies = json["proxies"]!!.jsonArray
        val groups = json["proxy-groups"]!!.jsonArray
        assertEquals(3, proxies.size)
        assertEquals(1, groups.size)

        val et = proxies[0].jsonObject
        assertEquals("ET-LOCAL", et["name"]!!.jsonPrimitive.content)
        assertEquals("socks5", et["type"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1", et["server"]!!.jsonPrimitive.content)
        assertEquals(11080, et["port"]!!.jsonPrimitive.content.toInt())
        assertNull(et["dialer-proxy"])
        assertNull(et["username"]) // ET-LOCAL 无认证

        val normal = proxies[1].jsonObject
        assertEquals("AWS-NORMAL", normal["name"]!!.jsonPrimitive.content)
        assertEquals("ET-LOCAL", normal["dialer-proxy"]!!.jsonPrimitive.content)
        assertEquals("u1", normal["username"]!!.jsonPrimitive.content)
        assertEquals("p1", normal["password"]!!.jsonPrimitive.content)
        assertEquals("10.126.126.10", normal["server"]!!.jsonPrimitive.content)

        val warp = proxies[2].jsonObject
        assertEquals("AWS-WARP", warp["name"]!!.jsonPrimitive.content)
        assertEquals("ET-LOCAL", warp["dialer-proxy"]!!.jsonPrimitive.content)

        val group = groups[0].jsonObject
        assertEquals("AWS-EXIT", group["name"]!!.jsonPrimitive.content)
        assertEquals("select", group["type"]!!.jsonPrimitive.content)
        val members = group["proxies"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("AWS-NORMAL", "AWS-WARP"), members)
    }

    @Test
    fun `compose returns null when disabled or invalid`() {
        assertNull(MihomoConfigComposer.composeOrNull(validConfig().copy(enabled = false)))
        assertNull(MihomoConfigComposer.composeOrNull(validConfig().copy(networkName = "")))
        assertNull(MihomoConfigComposer.composeOrNull(validConfig().copy(peers = emptyList())))
    }

    @Test
    fun `et-local never appears in any group and dialer always et-local`() {
        val obj = Json.parseToJsonElement(MihomoConfigComposer.composeOrNull(validConfig())!!).jsonObject
        obj["proxy-groups"]!!.jsonArray.forEach { g ->
            val members = g.jsonObject["proxies"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue(MihomoConfigComposer.PROXY_ET_LOCAL !in members)
        }
        obj["proxies"]!!.jsonArray.forEach { p ->
            val dp = p.jsonObject["dialer-proxy"]
            if (dp != null) assertEquals(MihomoConfigComposer.PROXY_ET_LOCAL, dp.jsonPrimitive.content)
        }
    }

    @Test
    fun `empty credentials are omitted`() {
        val obj = Json.parseToJsonElement(
            MihomoConfigComposer.composeOrNull(validConfig().copy(normalUser = "", normalPassword = ""))!!
        ).jsonObject
        val normal = obj["proxies"]!!.jsonArray[1].jsonObject
        assertNull(normal["username"])
        assertNull(normal["password"])
    }
}
