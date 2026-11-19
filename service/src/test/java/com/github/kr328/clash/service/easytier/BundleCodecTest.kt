package com.github.kr328.clash.service.easytier

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BundleCodecTest {
    private val config = EasyTierConfig(
        enabled = true,
        networkName = "test-net",
        networkSecret = "SECRET-DO-NOT-LEAK",
        ipv4 = "10.126.126.20/24",
        peers = listOf("tcp://peer.example.com:11010"),
        lightsailHost = "10.126.126.10",
        normalUser = "u1",
        normalPassword = "PASS-DO-NOT-LEAK",
    )

    private fun manifest(encrypted: Boolean = false) = BundleCodec.Manifest(
        format = BundleCodec.FORMAT,
        version = BundleCodec.VERSION,
        exportedAt = 1_700_000_000_000,
        encrypted = encrypted,
        profile = BundleCodec.ProfileMeta(
            name = "测试配置",
            type = "file",
            source = "",
            interval = 0,
        ),
        easytier = BundleCodec.EasyTierPayload.from(config),
        selections = mapOf("AWS-EXIT" to "AWS-NORMAL"),
    )

    private fun files() = mapOf(
        "config.yaml" to "mixed-port: 7890\nproxies: []\n".toByteArray(),
        "providers/abc.yaml" to "proxies: []\n".toByteArray(),
    )

    @Test
    fun `plaintext roundtrip keeps manifest and files`() {
        val bytes = BundleCodec.encode(manifest(), files(), password = null)
        assertFalse(BundleCodec.isEncrypted(bytes))

        val decoded = BundleCodec.decode(bytes, password = null)
        assertEquals("测试配置", decoded.manifest.profile.name)
        assertEquals(2, decoded.manifest.profile.fileCount)
        assertEquals("test-net", decoded.manifest.easytier.networkName)
        assertEquals("SECRET-DO-NOT-LEAK", decoded.manifest.easytier.networkSecret)
        assertEquals(mapOf("AWS-EXIT" to "AWS-NORMAL"), decoded.manifest.selections)
        assertEquals(2, decoded.files.size)
        assertArrayEquals(files()["config.yaml"], decoded.files["config.yaml"])
        assertArrayEquals(files()["providers/abc.yaml"], decoded.files["providers/abc.yaml"])
    }

    @Test
    fun `encrypted roundtrip requires the password`() {
        val bytes = BundleCodec.encode(manifest(), files(), password = "p@ssw0rd")
        assertTrue(BundleCodec.isEncrypted(bytes))

        try {
            BundleCodec.decode(bytes, password = null)
            fail("expected PasswordRequired")
        } catch (_: BundleCodec.PasswordRequired) {
        }

        val decoded = BundleCodec.decode(bytes, password = "p@ssw0rd")
        assertEquals("SECRET-DO-NOT-LEAK", decoded.manifest.easytier.networkSecret)
        assertEquals(2, decoded.files.size)
    }

    @Test
    fun `encrypted bundle does not leak secrets in plain text`() {
        val bytes = BundleCodec.encode(manifest(), files(), password = "p@ssw0rd")
        val raw = String(bytes, Charsets.ISO_8859_1)
        assertFalse(raw.contains("SECRET-DO-NOT-LEAK"))
        assertFalse(raw.contains("PASS-DO-NOT-LEAK"))
        assertFalse(raw.contains("peer.example.com"))
        assertFalse(raw.contains("测试配置"))
        assertFalse(raw.contains("config.yaml"))
    }

    @Test
    fun `wrong password is reported as corrupted`() {
        val bytes = BundleCodec.encode(manifest(), files(), password = "right")
        try {
            BundleCodec.decode(bytes, password = "wrong")
            fail("expected CorruptedException")
        } catch (e: BundleCodec.CorruptedException) {
            assertTrue(e.message.orEmpty().contains("password"))
        }
    }

    @Test
    fun `tampered payload is rejected`() {
        val bytes = BundleCodec.encode(manifest(), files(), password = "p")

        // 精确定位 payload.enc 并翻转其中一个字节，再重新打包（避免误改 ZIP 结构）
        val entries = unzip(bytes).toMutableMap()
        val payload = entries.getValue("payload.enc").copyOf()
        payload[payload.size / 2] = (payload[payload.size / 2].toInt() xor 0x01).toByte()
        entries["payload.enc"] = payload

        val result = runCatching { BundleCodec.decode(zip(entries), password = "p") }
        assertTrue("tampering must fail", result.isFailure)
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) out[e.name] = zip.readBytes()
            }
        }
        return out
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entries.forEach { (name, content) ->
                z.putNextEntry(ZipEntry(name))
                z.write(content)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `path traversal is rejected on encode`() {
        val evil = mapOf("../outside.txt" to "x".toByteArray())
        val result = runCatching { BundleCodec.encode(manifest(), evil, null) }
        assertTrue(result.isFailure)
    }

    @Test
    fun `path traversal is rejected on decode`() {
        val manifestJson = """
            {"format":"easyclash-bundle","version":1,"encrypted":false,
             "profile":{"name":"x","type":"file","source":"","interval":0,"file_count":1}}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("easyclash.json"))
            zip.write(manifestJson.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("profile/../../escape.txt"))
            zip.write("bad".toByteArray())
            zip.closeEntry()
        }
        val result = runCatching { BundleCodec.decode(out.toByteArray(), null) }
        assertTrue(result.isFailure)
    }

    @Test
    fun `header can be read without password`() {
        val bytes = BundleCodec.encode(manifest(), files(), password = "p")
        val header = BundleCodec.readManifestHeader(bytes)
        assertNotNull(header)
        assertTrue(header!!.encrypted)
        assertNotNull(header.kdf)
        assertNotNull(header.cipher)
        // 信封头不应包含内容信息
        assertEquals("", header.easytier.networkSecret)
        assertEquals("", header.profile.name)
    }

    @Test
    fun `unsupported version is rejected`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("easyclash.json"))
            zip.write("""{"format":"easyclash-bundle","version":99,"encrypted":false}""".toByteArray())
            zip.closeEntry()
        }
        val result = runCatching { BundleCodec.decode(out.toByteArray(), null) }
        assertTrue(result.isFailure)
    }

    @Test
    fun `extension and mime constants are stable`() {
        assertEquals("ecb", BundleCodec.EXTENSION)
        assertEquals("application/octet-stream", BundleCodec.MIME)
        assertNull(BundleCodec.readManifestHeader("not a zip".toByteArray()))
    }
}
