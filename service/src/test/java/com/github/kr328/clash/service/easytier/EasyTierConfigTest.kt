package com.github.kr328.clash.service.easytier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyTierConfigTest {
    private fun validConfig() = EasyTierConfig(
        enabled = true,
        networkName = "test-net",
        networkSecret = "s3cret",
        ipv4 = "10.126.126.20/24",
        peers = listOf("tcp://225284.example.com:11010", "udp://et1.example.com:11010"),
        socks5Port = 11080,
        lightsailHost = "10.126.126.10",
        normalPort = 1080,
        warpPort = 1081,
    )

    @Test
    fun `validate passes for complete config`() {
        assertTrue(validConfig().validate().isEmpty())
    }

    @Test
    fun `validate catches missing network name peers and bad ports`() {
        val cfg = EasyTierConfig(networkName = "", peers = emptyList(), socks5Port = 80)
        val errors = cfg.validate()
        assertTrue(errors.any { it.contains("network name") })
        assertTrue(errors.any { it.contains("peer") })
        assertTrue(errors.any { it.contains("SOCKS5 port") })
    }

    @Test
    fun `validate rejects invalid ipv4 and non-uri peers`() {
        val cfg = validConfig().copy(ipv4 = "999.999.1.1/24", peers = listOf("not-a-uri"))
        val errors = cfg.validate()
        assertTrue(errors.any { it.contains("invalid IPv4") })
        assertTrue(errors.any { it.contains("full URI") })
    }

    @Test
    fun `parseIpv4Cidr accepts bare ip cidr and rejects junk`() {
        assertEquals("10.0.0.1" to 24, EasyTierConfig().parseIpv4Cidr("10.0.0.1"))
        assertEquals("10.0.0.1" to 24, EasyTierConfig().parseIpv4Cidr("10.0.0.1/24"))
        assertEquals("10.0.0.1" to 30, EasyTierConfig().parseIpv4Cidr("10.0.0.1/30"))
        assertNull(EasyTierConfig().parseIpv4Cidr("abc"))
        assertNull(EasyTierConfig().parseIpv4Cidr("::1")) // IPv6 不接受
        assertNull(EasyTierConfig().parseIpv4Cidr("10.0.0.1/xx"))
    }

    @Test
    fun `toToml emits v2_6_4 fields with no_tun and loopback socks5`() {
        val toml = validConfig().toToml()
        assertTrue(toml.contains("instance_name = \"easyclash-android\""))
        assertTrue(toml.contains("[network_identity]"))
        assertTrue(toml.contains("network_name = \"test-net\""))
        assertTrue(toml.contains("network_secret = \"s3cret\""))
        assertTrue(toml.contains("[[peer]]"))
        assertTrue(toml.contains("uri = \"tcp://225284.example.com:11010\""))
        assertEquals(2, Regex("\\[\\[peer\\]\\]").findAll(toml).count())
        assertTrue(toml.contains("socks5_proxy = \"socks5://127.0.0.1:11080\""))
        assertTrue(toml.contains("[flags]"))
        assertTrue(toml.contains("no_tun = true"))
        assertFalse(toml.contains("inst_name")) // 上游 example_config 的错误字段名
        assertFalse(toml.contains("peers ="))
    }

    @Test
    fun `toToml omits empty secret`() {
        val toml = validConfig().copy(networkSecret = "").toToml()
        assertFalse(toml.contains("network_secret"))
    }

    @Test
    fun `toMaskedToml hides secret and peer hosts`() {
        val masked = validConfig().toMaskedToml()
        assertFalse(masked.contains("s3cret"))
        assertTrue(masked.contains("network_secret = \"***\""))
        assertFalse(masked.contains("225284.example.com:11010"))
        assertTrue(masked.contains("tcp://***:11010"))
    }

    @Test
    fun `ipv4 without cidr is normalized to slash24 in toml`() {
        val toml = validConfig().copy(ipv4 = "10.126.126.20").toToml()
        assertTrue(toml.contains("ipv4 = \"10.126.126.20/24\""))
    }
}
