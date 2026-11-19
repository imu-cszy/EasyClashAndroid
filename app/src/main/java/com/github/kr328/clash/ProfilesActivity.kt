package com.github.kr328.clash

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
import kotlinx.coroutines.withContext
import java.util.*
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R

class ProfilesActivity : BaseActivity<ProfilesDesign>() {
    override suspend fun main() {
        val design = ProfilesDesign(this)

        setContentDesign(design)

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
                        }
                    }
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

    /** 导入配置包（明文或加密） */
    private suspend fun importBundle() {
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
