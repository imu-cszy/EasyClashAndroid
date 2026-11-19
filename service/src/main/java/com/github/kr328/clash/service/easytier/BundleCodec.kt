package com.github.kr328.clash.service.easytier

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 配置包（.ecb）编解码。
 *
 * 内容为**整个 profile 目录**（config.yaml + providers/ + rule-providers/）+ EasyTier 全部配置
 * + 已选策略。容器是 ZIP：
 *
 *   明文：easyclash.json（完整 manifest） + profile/<相对路径>
 *   加密：easyclash.json（仅信封头） + payload.enc（AES-GCM(内层 ZIP：manifest + profile/<相对路径>)）
 *
 * 加密时 manifest 也放进密文，避免网络密钥 / SOCKS5 密码以明文出现在文件里。
 *
 * KDF：PBKDF2-HMAC-SHA256（默认 210000 次，16B salt）→ AES-256-GCM（12B IV，128bit tag，
 * AAD 绑定格式版本，防降级）。
 */
object BundleCodec {
    const val FORMAT = "easyclash-bundle"
    const val VERSION = 1
    const val EXTENSION = "ecb"
    const val MIME = "application/octet-stream"

    private const val MANIFEST_ENTRY = "easyclash.json"
    private const val PAYLOAD_ENTRY = "payload.enc"
    private const val PROFILE_PREFIX = "profile/"
    private const val DEFAULT_ITERATIONS = 210_000
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /** 安全上限：单个配置包最大 32MB，最多 512 个文件，单文件 16MB */
    const val MAX_BUNDLE_BYTES = 32 * 1024 * 1024
    private const val MAX_ENTRIES = 512
    private const val MAX_ENTRY_BYTES = 16 * 1024 * 1024

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    class PasswordRequired : Exception("bundle is encrypted")
    class CorruptedException(message: String) : Exception(message)

    // ---------------------------------------------------------------- 模型

    @Serializable
    data class KdfParams(
        val algo: String = "pbkdf2-hmac-sha256",
        val iterations: Int = DEFAULT_ITERATIONS,
        @SerialName("salt") val salt: String,
    )

    @Serializable
    data class CipherParams(
        val algo: String = "aes-256-gcm",
        @SerialName("iv") val iv: String,
    )

    @Serializable
    data class ProfileMeta(
        val name: String = "",
        /** "url" / "file" */
        val type: String = "file",
        val source: String = "",
        val interval: Long = 0,
        val ageSecretKey: String? = null,
        @SerialName("file_count") val fileCount: Int = 0,
    )

    @Serializable
    data class EasyTierPayload(
        val enabled: Boolean = false,
        val instanceName: String = "",
        val networkName: String = "",
        val networkSecret: String = "",
        val hostname: String = "",
        val ipv4: String = "",
        val peers: List<String> = emptyList(),
        val socks5Port: Int = 11080,
        val defaultProtocol: String = "tcp",
        val latencyFirst: Boolean = false,
        val lightsailHost: String = "",
        val normalPort: Int = 1080,
        val warpPort: Int = 1081,
        val normalUser: String = "",
        val normalPassword: String = "",
        val warpUser: String = "",
        val warpPassword: String = "",
    ) {
        fun toConfig(): EasyTierConfig = EasyTierConfig(
            enabled = enabled,
            instanceName = instanceName,
            networkName = networkName,
            networkSecret = networkSecret,
            hostname = hostname,
            ipv4 = ipv4,
            peers = peers,
            socks5Port = socks5Port,
            defaultProtocol = defaultProtocol,
            latencyFirst = latencyFirst,
            lightsailHost = lightsailHost,
            normalPort = normalPort,
            warpPort = warpPort,
            normalUser = normalUser,
            normalPassword = normalPassword,
            warpUser = warpUser,
            warpPassword = warpPassword,
        )

        companion object {
            fun from(cfg: EasyTierConfig): EasyTierPayload = EasyTierPayload(
                enabled = cfg.enabled,
                instanceName = cfg.instanceName,
                networkName = cfg.networkName,
                networkSecret = cfg.networkSecret,
                hostname = cfg.hostname,
                ipv4 = cfg.ipv4,
                peers = cfg.peers,
                socks5Port = cfg.socks5Port,
                defaultProtocol = cfg.defaultProtocol,
                latencyFirst = cfg.latencyFirst,
                lightsailHost = cfg.lightsailHost,
                normalPort = cfg.normalPort,
                warpPort = cfg.warpPort,
                normalUser = cfg.normalUser,
                normalPassword = cfg.normalPassword,
                warpUser = cfg.warpUser,
                warpPassword = cfg.warpPassword,
            )
        }
    }

    @Serializable
    data class Manifest(
        val format: String = FORMAT,
        val version: Int = VERSION,
        @SerialName("exported_at") val exportedAt: Long = 0,
        val encrypted: Boolean = false,
        val kdf: KdfParams? = null,
        val cipher: CipherParams? = null,
        val profile: ProfileMeta = ProfileMeta(),
        val easytier: EasyTierPayload = EasyTierPayload(),
        /** 策略组已选项：group -> proxy */
        val selections: Map<String, String> = emptyMap(),
    )

    data class Decoded(
        val manifest: Manifest,
        /** 相对 profile 目录的路径 -> 内容（如 "config.yaml"、"providers/xxx"） */
        val files: Map<String, ByteArray>,
    )

    // ---------------------------------------------------------------- 编码

    /**
     * @param files profile 目录下的文件（相对路径，不允许 .. 或绝对路径）
     * @param password null/空白 = 明文导出
     */
    fun encode(manifest: Manifest, files: Map<String, ByteArray>, password: String?): ByteArray {
        val safeFiles = files.mapKeys { (path, _) -> sanitize(path) }
        val full = manifest.copy(
            format = FORMAT,
            version = VERSION,
            encrypted = password.isNullOrEmpty().not(),
            profile = manifest.profile.copy(fileCount = safeFiles.size),
        )
        val plain = buildZip(full, safeFiles)

        if (password.isNullOrEmpty()) {
            return plain
        }

        val salt = ByteArray(SALT_LEN).also { java.security.SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { java.security.SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt, DEFAULT_ITERATIONS)
        val cipherText = aesGcm(true, key, iv, plain)

        val header = Manifest(
            format = FORMAT,
            version = VERSION,
            exportedAt = full.exportedAt,
            encrypted = true,
            kdf = KdfParams(salt = hex(salt)),
            cipher = CipherParams(iv = hex(iv)),
            profile = ProfileMeta(fileCount = safeFiles.size),
        )
        return buildZipRaw(header, mapOf(PAYLOAD_ENTRY to cipherText))
    }

    // ---------------------------------------------------------------- 解码

    fun decode(bytes: ByteArray, password: String?): Decoded {
        if (bytes.size > MAX_BUNDLE_BYTES) throw CorruptedException("bundle too large")
        val outer = readZip(bytes)
        val headerBytes = outer[MANIFEST_ENTRY] ?: throw CorruptedException("missing $MANIFEST_ENTRY")
        val header = parseManifest(headerBytes)

        if (header.format != FORMAT) throw CorruptedException("unknown format: ${header.format}")
        if (header.version > VERSION) throw CorruptedException("bundle version ${header.version} is newer")

        if (!header.encrypted) {
            return Decoded(header, collectProfileFiles(outer))
        }

        val payload = outer[PAYLOAD_ENTRY] ?: throw CorruptedException("missing $PAYLOAD_ENTRY")
        if (password.isNullOrEmpty()) throw PasswordRequired()
        val kdf = header.kdf ?: throw CorruptedException("missing kdf params")
        val cipher = header.cipher ?: throw CorruptedException("missing cipher params")
        val key = deriveKey(password, unhex(kdf.salt), kdf.iterations)
        val plain = try {
            aesGcm(false, key, unhex(cipher.iv), payload)
        } catch (e: Exception) {
            throw CorruptedException("wrong password or corrupted bundle")
        }
        val inner = readZip(plain)
        val manifestBytes = inner[MANIFEST_ENTRY] ?: throw CorruptedException("missing inner manifest")
        return Decoded(parseManifest(manifestBytes), collectProfileFiles(inner))
    }

    fun isEncrypted(bytes: ByteArray): Boolean = runCatching {
        readZip(bytes)[MANIFEST_ENTRY]?.let { parseManifest(it).encrypted } ?: false
    }.getOrDefault(false)

    /** 只读取 manifest（加密包也能读信封头，用于展示"需要密码"） */
    fun readManifestHeader(bytes: ByteArray): Manifest? = runCatching {
        readZip(bytes)[MANIFEST_ENTRY]?.let { parseManifest(it) }
    }.getOrNull()

    // ---------------------------------------------------------------- 内部

    /** profile 文件写入 profile/ 前缀 */
    private fun buildZip(manifest: Manifest, files: Map<String, ByteArray>): ByteArray =
        buildZipRaw(manifest, files.mapKeys { PROFILE_PREFIX + it.key })

    /** 原样写入条目名（payload.enc 等不能带 profile/ 前缀） */
    private fun buildZipRaw(manifest: Manifest, entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(json.encodeToString(Manifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (++count > MAX_ENTRIES) throw CorruptedException("too many entries")
                val data = zip.readAtMost(MAX_ENTRY_BYTES)
                    ?: throw CorruptedException("entry too large: ${entry.name}")
                result[entry.name] = data
            }
        }
        return result
    }

    private fun java.util.zip.ZipInputStream.readAtMost(limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = read(buf)
            if (n <= 0) break
            if (out.size() + n > limit) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun collectProfileFiles(entries: Map<String, ByteArray>): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        entries.forEach { (name, content) ->
            if (name.startsWith(PROFILE_PREFIX) && name.length > PROFILE_PREFIX.length) {
                files[sanitize(name.substring(PROFILE_PREFIX.length))] = content
            }
        }
        return files
    }

    private fun parseManifest(bytes: ByteArray): Manifest =
        runCatching {
            json.decodeFromString(Manifest.serializer(), String(bytes, Charsets.UTF_8))
        }.getOrElse { throw CorruptedException("invalid manifest: ${it.message}") }

    /** 拒绝绝对路径、..、反斜杠，防止解包时逃逸到 profile 目录外 */
    private fun sanitize(path: String): String {
        val normalized = path.replace('\\', '/').trimStart('/')
        val parts = normalized.split('/')
        if (normalized.isEmpty() || parts.any { it.isEmpty() || it == "." || it == ".." }) {
            throw CorruptedException("illegal path in bundle: $path")
        }
        return parts.joinToString("/")
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): ByteArray {
        if (iterations !in 10_000..2_000_000) throw CorruptedException("bad kdf iterations")
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun aesGcm(encrypt: Boolean, key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, iv),
        )
        cipher.updateAAD("$FORMAT:v$VERSION".toByteArray(Charsets.UTF_8))
        return cipher.doFinal(input)
    }

    /** salt/iv 很短，直接用 hex，避免 android.util.Base64 在 JVM 单测里不可用 */
    private fun hex(bytes: ByteArray): String {
        val table = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(table[v ushr 4]).append(table[v and 0x0F])
        }
        return sb.toString()
    }

    private fun unhex(text: String): ByteArray {
        if (text.length % 2 != 0) throw CorruptedException("invalid hex string")
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            out[i] = text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
