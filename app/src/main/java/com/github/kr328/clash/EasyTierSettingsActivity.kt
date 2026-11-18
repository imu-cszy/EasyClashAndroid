package com.github.kr328.clash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import com.github.kr328.clash.common.compat.registerReceiverCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.design.EasyTierSettingsDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.service.easytier.EasyTierConfigStore
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

class EasyTierSettingsActivity : BaseActivity<EasyTierSettingsDesign>() {
    private val store by lazy { EasyTierConfigStore(this) }

    private var pendingDiagDialog: android.app.AlertDialog? = null

    private val diagReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.`package` != packageName) return
            val text = intent.getStringExtra(Intents.EXTRA_DIAG_TEXT) ?: return
            showDiagnostics(text)
        }
    }

    override suspend fun main() {
        var config = store.load(this)

        val design = EasyTierSettingsDesign(this, config) { updated ->
            config = updated
            store.save(this, updated)
        }

        setContentDesign(design)

        registerReceiverCompat(diagReceiver, IntentFilter().apply {
            addAction(Intents.ACTION_EASYTIER_DIAG)
        })

        try {
            while (isActive) {
                select<Unit> {
                    events.onReceive { }
                    design.requests.onReceive { request ->
                        when (request) {
                            EasyTierSettingsDesign.Request.ShowDiagnostics ->
                                requestDiagnostics()
                            EasyTierSettingsDesign.Request.RestartRequired ->
                                Toast.makeText(
                                    this@EasyTierSettingsActivity,
                                    R.string.et_restart_required,
                                    Toast.LENGTH_SHORT
                                ).show()
                        }
                    }
                }
            }
        } finally {
            runCatching { unregisterReceiver(diagReceiver) }
            pendingDiagDialog?.dismiss()
            pendingDiagDialog = null
        }
    }

    private fun requestDiagnostics() {
        val intent = Intent(Intents.ACTION_EASYTIER_REQUEST_DIAG)
            .setPackage(packageName)
        sendBroadcast(intent)
        Toast.makeText(this, R.string.et_diag_requesting, Toast.LENGTH_SHORT).show()
        Log.d("EasyTier diagnostics requested")
    }

    private fun showDiagnostics(text: String) {
        pendingDiagDialog?.dismiss()
        pendingDiagDialog = android.app.AlertDialog.Builder(this)
            .setTitle(R.string.et_view_diagnostics)
            .setMessage(text)
            .setPositiveButton(android.R.string.copy) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("EasyTier diagnostics", text)
                )
                Toast.makeText(this, R.string.et_diag_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
