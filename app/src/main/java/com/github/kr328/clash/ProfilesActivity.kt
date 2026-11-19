package com.github.kr328.clash

import com.github.kr328.clash.ui.bindBottomNav

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.ProfilesDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.easytier.BundleCodec
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.BundleDialogs
import com.github.kr328.clash.util.BundleManager
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.withContext
import java.util.*
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R

class ProfilesActivity : BaseActivity<ProfilesDesign>() {
    /** 扫码结果（只传 URL；失败给 null） */
    private val scannedLinks = kotlinx.coroutines.channels.Channel<String?>(kotlinx.coroutines.channels.Channel.CONFLATED)

    private val scanLauncher = registerForActivityResult(
        io.github.g00fy2.quickie.ScanQRCode()
    ) { result ->
        when (result) {
            is io.github.g00fy2.quickie.QRResult.QRSuccess ->
                scannedLinks.trySend(
                    result.content.rawValue ?: result.content.rawBytes?.let { String(it) }
                )
            is io.github.g00fy2.quickie.QRResult.QRMissingPermission -> {
                lifecycleScope.launch {
                    design?.showToast(getString(R.string.import_from_qr_no_permission), ToastDuration.Long)
                }
                scannedLinks.trySend(null)
            }
            is io.github.g00fy2.quickie.QRResult.QRError -> {
                lifecycleScope.launch {
                    design?.showToast(getString(R.string.import_from_qr_exception), ToastDuration.Long)
                }
                scannedLinks.trySend(null)
            }
            else -> Unit
        }
    }

    override suspend fun main() {
        val design = ProfilesDesign(this)

        setContentDesign(design)

        bindBottomNav(design.bottomNav, com.github.kr328.clash.design.view.BottomNavBar.Destination.Profiles)

        val ticker = ticker(TimeUnit.MINUTES.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart, Event.ProfileChanged -> {
                            design.fetch()
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        ProfilesDesign.Request.Create ->
                            startActivity(NewProfileActivity::class.intent)
                        ProfilesDesign.Request.UpdateAll ->
                            withProfile {
                                try {
                                    queryAll().forEach { p ->
                                        if (p.imported && p.type != Profile.Type.File)
                                            update(p.uuid)
                                    }
                                }
                                finally {
                                    withContext(Dispatchers.Main) {
                                        design.finishUpdateAll();
                                    }
                                }
                            }
                        is ProfilesDesign.Request.Update ->
                            withProfile { update(it.profile.uuid) }
                        is ProfilesDesign.Request.Delete ->
                            withProfile { delete(it.profile.uuid) }
                        is ProfilesDesign.Request.Edit ->
                            startActivity(PropertiesActivity::class.intent.setUUID(it.profile.uuid))
                        is ProfilesDesign.Request.EditConfig ->
                            startActivity(ConfigEditorActivity::class.intent.setUUID(it.profile.uuid))
                        is ProfilesDesign.Request.Active -> {
                            withProfile {
                                if (it.profile.imported)
                                    setActive(it.profile)
                                else
                                    design.requestSave(it.profile)
                            }
                        }
                        is ProfilesDesign.Request.Duplicate -> {
                            val uuid = withProfile { clone(it.profile.uuid) }

                            startActivity(PropertiesActivity::class.intent.setUUID(uuid))
                        }
                        is ProfilesDesign.Request.ShareQrCode ->
                            design.showProfileQrCode(it.profile)
                        is ProfilesDesign.Request.ExportBundle -> launch {
                            exportBundle(it.profile)
                        }
                        ProfilesDesign.Request.ImportBundle -> launch {
                            importBundle()
                        }                    }
                }
                scannedLinks.onReceive { url ->
                    if (url != null) importBundleFromUrl(url)
                }
                if (activityStarted) {
                    ticker.onReceive {
                        design.updateElapsed()
                    }
                }
            }
        }
    }

    private suspend fun ProfilesDesign.fetch() {
        withProfile {
            patchProfiles(queryAll())
        }
    }

    /** 导出整个 profile 目录 + EasyTier 配置 */
    private suspend fun exportBundle(profile: Profile) {
        val choice = BundleDialogs.requestExportChoice(this) ?: return

        val bytes = try {
            BundleManager.export(this, profile, choice.password)
        } catch (e: Exception) {
            design?.showToast(
                getString(R.string.bundle_export_failed) + ": " + (e.message ?: ""),
                ToastDuration.Long,
            )
            return
        }

        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date())
        val suggested = "easyclash-$stamp.${BundleCodec.EXTENSION}"

        val uri = try {
            startActivityForResult(
                androidx.activity.result.contract.ActivityResultContracts.CreateDocument(BundleCodec.MIME),
                suggested,
            )
        } catch (e: Exception) {
            null
        } ?: return

        try {
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw java.io.IOException("cannot open output stream")
            }
            design?.showToast(getString(R.string.bundle_exported), ToastDuration.Long)
        } catch (e: Exception) {
            design?.showToast(
                getString(R.string.bundle_export_failed) + ": " + (e.message ?: ""),
                ToastDuration.Long,
            )
        }
    }

    /** 导入配置包：先选来源（文件 / 链接·二维码） */
    private suspend fun importBundle() {
        when (BundleDialogs.requestImportMethod(this)) {
            BundleDialogs.ImportMethod.File -> importBundleFromFile()
            BundleDialogs.ImportMethod.Link -> when (val link = BundleDialogs.requestImportLink(this)) {
                is BundleDialogs.ImportLink.Url -> importBundleFromUrl(link.url)
                BundleDialogs.ImportLink.Scan -> scanLauncher.launch(null)
                null -> Unit
            }
            null -> Unit
        }
    }

    private suspend fun importBundleFromFile() {
        val uri = try {
            startActivityForResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                arrayOf("*/*"),
            )
        } catch (e: Exception) {
            null
        } ?: return

        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
        } ?: return

        importBundleBytes(bytes)
    }

    /**
     * 从局域网链接拉取配置包。
     *
     * 这是手机 ↔ 电脑互传的主路径：电脑端起一个临时 HTTP 服务并生成二维码，
     * 手机扫码（或手输 URL）即可拿到同一份 `.ecb`，不经过任何第三方。
     */
    private suspend fun importBundleFromUrl(url: String) {
        design?.showToast(getString(R.string.bundle_downloading), ToastDuration.Short)

        val result = withContext(Dispatchers.IO) { downloadBundle(url) }

        if (result.isFailure) {
            com.github.kr328.clash.common.log.Log.w(
                "Bundle download failed: ${result.exceptionOrNull()?.message}"
            )
        }

        result.onSuccess { importBundleBytes(it) }
            .onFailure {
                design?.showToast(
                    getString(R.string.bundle_download_failed, it.message ?: url),
                    ToastDuration.Long,
                )
            }
    }

    private fun downloadBundle(url: String): Result<ByteArray> = runCatching {
        require(url.startsWith("http://") || url.startsWith("https://")) {
            getString(R.string.bundle_link_invalid)
        }

        val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }

        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }

            val limit = 32L * 1024 * 1024
            val out = java.io.ByteArrayOutputStream()

            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L

                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break

                    total += read
                    if (total > limit) throw IllegalStateException(getString(R.string.bundle_too_large))

                    out.write(buffer, 0, read)
                }
            }

            out.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    /** 明文/加密包的统一收尾：问密码 → 导入 → 刷新列表 */
    private suspend fun importBundleBytes(bytes: ByteArray) {
        val password = try {
            if (withContext(Dispatchers.IO) { BundleCodec.isEncrypted(bytes) }) {
                BundleDialogs.requestPassword(this, R.string.bundle_import_password_title)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }

        try {
            BundleManager.import(this, bytes, password)
            design?.showToast(getString(R.string.bundle_imported), ToastDuration.Long)
            design?.fetch()
        } catch (e: BundleCodec.PasswordRequired) {
            design?.showToast(
                getString(R.string.bundle_import_password_title),
                ToastDuration.Long,
            )
        } catch (e: Exception) {
            com.github.kr328.clash.common.log.Log.w("Bundle import failed: ${e.message}")
            design?.showToast(
                getString(R.string.bundle_import_failed, e.message ?: ""),
                ToastDuration.Long,
            )
        }
    }

    override fun onProfileUpdateCompleted(uuid: UUID?) {
        if(uuid == null)
            return;
        launch {
            var name: String? = null;
            withProfile {
                name = queryByUUID(uuid)?.name
            }
            design?.showToast(
                getString(R.string.toast_profile_updated_complete, name),
                ToastDuration.Long
            )
        }
    }
    override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {
        if(uuid == null)
            return;
        launch {
            var name: String? = null;
            withProfile {
                name = queryByUUID(uuid)?.name
            }
            design?.showToast(
                getString(R.string.toast_profile_updated_failed, name, reason),
                ToastDuration.Long
            ){
                setAction(R.string.edit) {
                    startActivity(PropertiesActivity::class.intent.setUUID(uuid))
                }
            }
        }
    }
}
