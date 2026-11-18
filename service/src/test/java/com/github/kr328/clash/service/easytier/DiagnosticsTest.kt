package com.github.kr328.clash.service.easytier

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    @Test
    fun `mask hides secret password token pairs`() {
        val masked = Diagnostics.mask("network_secret=abc123 password: hello token=\"x\" private_key = k1")
        assertFalse(masked.contains("abc123"))
        assertFalse(masked.contains("hello"))
        assertFalse(masked.contains("\"x\""))
        assertFalse(masked.contains("k1"))
    }

    @Test
    fun `mask hides uri hosts but keeps scheme and port`() {
        val masked = Diagnostics.mask("peer uri=tcp://225284.example.com:11010 and udp://et1.example.com:11011")
        assertFalse(masked.contains("225284.example.com"))
        assertFalse(masked.contains("et1.example.com"))
        assertTrue(masked.contains("tcp://***:11010"))
        assertTrue(masked.contains("udp://***:11011"))
    }

    @Test
    fun `mask handles quoted toml values`() {
        val masked = Diagnostics.mask("""network_secret = "topsecret" password = "pw2\"""")
        assertFalse(masked.contains("topsecret"))
        assertFalse(masked.contains("pw2"))
    }

    @Test
    fun `ring buffer keeps latest entries`() {
        val d = Diagnostics(capacity = 3)
        repeat(5) { i -> d.log(Diagnostics.Category.RUNTIME, "s$i", "m$i") }
        val snap = d.snapshot()
        assertTrue(snap.size == 3)
        assertTrue(snap.any { it.stage == "s2" })
        assertFalse(snap.any { it.stage == "s0" })
    }

    @Test
    fun `export contains entries and masks content`() {
        val d = Diagnostics()
        d.log(Diagnostics.Category.CONFIG, "toml", "network_secret = \"abc\"")
        val out = d.exportMasked()
        assertTrue(out.contains("CONFIG/toml"))
        assertFalse(out.contains("\"abc\""))
    }
}
