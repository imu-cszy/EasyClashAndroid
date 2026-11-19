package com.github.kr328.clash.util

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.easytier.BundleCodec
import com.github.kr328.clash.service.easytier.EasyTierConfigStore
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

/**
 * 配置包导出/导入。
 *
 * 导出：整个 profile 目录（config.yaml + providers/ + rule-providers/）+ EasyTier 全部配置。
 * 导入：解码 → 校验 → 通过 [withProfile] 新建 profile（pending → commit）+ 写入 EasyTier 配置。
 */
object BundleManager {
    private const val MAX_FILE_BYTES = 16L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 32L * 1024 * 1024

    suspend fun export(context: Context, profile: Profile, password: String?): ByteArray =
        withContext(Dispatchers.IO) {
            val dir = context.importedDir.resolve(profile.uuid.toString())
            val files = readTree(dir)

            val manifest = BundleCodec.Manifest(
                exportedAt = System.currentTimeMillis(),
                profile = BundleCodec.ProfileMeta(
                    name = profile.name,
                    type = if (profile.type == Profile.Type.Url) "url" else "file",
                    source = profile.source,
                    interval = profile.interval,
                    ageSecretKey = profile.ageSecretKey,
                ),
                easytier = BundleCodec.EasyTierPayload.from(EasyTierConfigStore(context).load(context)),
                selections = emptyMap(),
            )

            BundleCodec.encode(manifest, files, password)
        }

    /**
     * @return 新 profile 的 uuid
     * @throws BundleCodec.PasswordRequired 需要密码
     * @throws BundleCodec.CorruptedException 密码错误 / 文件损坏 / 配置非法
     */
    suspend fun import(context: Context, bytes: ByteArray, password: String?): UUID {
        val decoded = BundleCodec.decode(bytes, password)
        val manifest = decoded.manifest

        if (decoded.files.isEmpty()) {
            throw BundleCodec.CorruptedException("配置包里没有文件")
        }

        val name = manifest.profile.name.ifBlank { "Imported" }
        val uuid = withProfile {
            create(Profile.Type.File, name, "", manifest.profile.ageSecretKey?.takeIf { it.isNotBlank() })
        }

        try {
            val pending = context.pendingDir.resolve(uuid.toString())
            withContext(Dispatchers.IO) {
                pending.deleteRecursively()
                pending.mkdirs()

                decoded.files.forEach { (path, content) ->
                    val target = File(pending, path)
                    target.parentFile?.mkdirs()
                    target.writeBytes(content)
                }
            }

            // 先用 mihomo 校验（不应用），坏配置不进入 imported
            val error = withContext(Dispatchers.IO) { Clash.validateProfile(pending) }
            if (error != null) {
                throw BundleCodec.CorruptedException("配置校验失败：$error")
            }

            withProfile { commit(uuid, null) }
        } catch (e: Exception) {
            Log.w("Bundle import failed: ${e.message}")
            runCatching { withProfile { delete(uuid) } }
            throw e
        }

        // EasyTier 配置（含凭据）落库时由 SecretStore 用 Keystore 重新加密
        withContext(Dispatchers.IO) {
            EasyTierConfigStore(context).save(context, manifest.easytier.toConfig())
        }

        return uuid
    }

    private fun readTree(root: File): Map<String, ByteArray> {
        if (!root.isDirectory) throw BundleCodec.CorruptedException("profile 目录不存在")

        val result = LinkedHashMap<String, ByteArray>()
        var total = 0L

        root.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                // 备份文件不进包
                if (relative.endsWith(".bak") || relative.endsWith(".tmp")) return@forEach

                val length = file.length()
                if (length > MAX_FILE_BYTES) {
                    throw BundleCodec.CorruptedException("文件过大：$relative")
                }
                total += length
                if (total > MAX_TOTAL_BYTES) {
                    throw BundleCodec.CorruptedException("配置包过大（超过 32MB）")
                }
                result[relative] = file.readBytes()
            }

        return result
    }
}
