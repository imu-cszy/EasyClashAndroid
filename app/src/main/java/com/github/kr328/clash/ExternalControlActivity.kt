package com.github.kr328.clash

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.Shortcuts
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.util.*
import com.github.kr328.clash.design.R

class ExternalControlActivity : Activity(), CoroutineScope by MainScope() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        // 先按真实状态刷一次桌面图标：服务被系统杀掉时图标可能还是旧的，这里能自愈
        val runningNow = isClashRunning()
        Shortcuts.updateToggleShortcut(this, runningNow)

        when(intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return finish()
                val url = uri.getQueryParameter("url") ?: return finish()

                launch {
                    val uuid = withProfile {
                        val type = when (uri.getQueryParameter("type")?.lowercase(Locale.getDefault())) {
                            "url" -> Profile.Type.Url
                            "file" -> Profile.Type.File
                            else -> Profile.Type.Url
                        }
                        val name = uri.getQueryParameter("name") ?: getString(R.string.new_profile)

                        val parsedInterval = uri.getQueryParameter("update-interval")?.toLongOrNull() ?: 0L
                        val updateInterval = if (parsedInterval > 0) parsedInterval.coerceAtLeast(15L) else 0L
                        val intervalMs = java.util.concurrent.TimeUnit.MINUTES.toMillis(updateInterval)

                        create(type, name).also {
                            patch(it, name, url, intervalMs, null)
                        }
                    }
                    startActivity(PropertiesActivity::class.intent.setUUID(uuid))
                    finish()
                }
                return
            }

            Intents.ACTION_TOGGLE_CLASH -> {
                if (runningNow) {
                    stopClash()
                } else {
                    startClash()
                }
            }
            
            Intents.ACTION_START_CLASH -> {
                if (runningNow) {
                    Toast.makeText(this, R.string.external_control_started, Toast.LENGTH_LONG).show()
                } else {
                    startClash()
                }
            }
            
            Intents.ACTION_STOP_CLASH -> {
                stopClash()
            }
        }
        return finish()
    }

    private fun isClashRunning(): Boolean {
        return StatusClient(this).currentProfile() != null
    }

    private fun startClash() {
//        if (currentProfile == null) {
//            Toast.makeText(this, R.string.no_profile_selected, Toast.LENGTH_LONG).show()
//            return
//        }
        val vpnRequest = startClashService()
        if (vpnRequest != null) {
            Toast.makeText(this, R.string.unable_to_start_vpn, Toast.LENGTH_LONG).show()
            return
        }
        Shortcuts.updateToggleShortcut(this, true)
        Toast.makeText(this, R.string.external_control_started, Toast.LENGTH_LONG).show()
    }

    private fun stopClash() {
        stopClashService()
        Shortcuts.updateToggleShortcut(this, false)
        Toast.makeText(this, R.string.external_control_stopped, Toast.LENGTH_LONG).show()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
