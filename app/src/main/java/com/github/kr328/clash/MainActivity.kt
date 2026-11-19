package com.github.kr328.clash

import com.github.kr328.clash.ui.bindBottomNav

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.core.bridge.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R as DesignR

class MainActivity : BaseActivity<MainDesign>() {
    /** EasyTier 最近一次状态广播（null = 未启用 EasyTier 或尚未收到） */
    private var easyTierState: String? = null
    private var easyTierError: String? = null
    private var easyTierIp: String? = null

    private val easyTierStates = Channel<String?>(Channel.CONFLATED)

    /** HOME 五态：VPN 没起来就是 Stopped；起来后再看 EasyTier 的细分状态 */
    private fun homeStatus(): MainDesign.Status {
        if (!clashRunning) return MainDesign.Status.Stopped

        return when (easyTierState) {
            "STARTING", "WAITING_FOR_PEER", "CHECKING_LOCAL_SOCKS",
            "CHECKING_LIGHTSAIL", "RECONNECTING" -> MainDesign.Status.Connecting
            "DEGRADED" -> MainDesign.Status.Degraded
            "FAILED" -> MainDesign.Status.Error
            else -> MainDesign.Status.Connected
        }
    }

    private val easyTierReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.`package` != packageName) return
            easyTierStates.trySend(intent.getStringExtra(Intents.EXTRA_STATE_JSON))
        }
    }

    override suspend fun main() {
        val design = MainDesign(this)

        setContentDesign(design)

        bindBottomNav(design.bottomNav, com.github.kr328.clash.design.view.BottomNavBar.Destination.Dashboard)

        design.fetch()

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        ContextCompat.registerReceiver(
            this,
            easyTierReceiver,
            IntentFilter(Intents.ACTION_EASYTIER_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        try {
            while (isActive) {
                select<Unit> {
                    easyTierStates.onReceive { design.applyEasyTierState(it) }
                    events.onReceive {
                        when (it) {
                            Event.ActivityStart,
                            Event.ServiceRecreated,
                            Event.ClashStop, Event.ClashStart,
                            Event.ProfileLoaded, Event.ProfileChanged -> design.fetch()
                            else -> Unit
                        }
                    }
                    design.requests.onReceive {
                        when (it) {
                            MainDesign.Request.ToggleStatus -> {
                                if (clashRunning)
                                    stopClashService()
                                else
                                    design.startClash()
                            }
                            MainDesign.Request.OpenProxy ->
                                startActivity(ProxyActivity::class.intent)
                            MainDesign.Request.OpenProfiles ->
                                startActivity(ProfilesActivity::class.intent)
                            MainDesign.Request.OpenProviders ->
                                startActivity(ProvidersActivity::class.intent)
                            MainDesign.Request.OpenLogs -> {
                                if (LogcatService.running) {
                                    startActivity(LogcatActivity::class.intent)
                                } else {
                                    startActivity(LogsActivity::class.intent)
                                }
                            }
                            MainDesign.Request.OpenSettings ->
                                startActivity(SettingsActivity::class.intent)
                            MainDesign.Request.OpenHelp ->
                                startActivity(HelpActivity::class.intent)
                            MainDesign.Request.OpenAbout ->
                                design.showAbout(queryAppVersionName())
                        }
                    }
                    if (clashRunning) {
                        ticker.onReceive {
                            design.fetchTraffic()
                        }
                    }
                }
            }
        } finally {
            runCatching { unregisterReceiver(easyTierReceiver) }
        }
    }

    private suspend fun MainDesign.applyEasyTierState(json: String?) {
        if (json != null) {
            runCatching {
                val payload = JSONObject(json)

                easyTierState = payload.optString("state").ifEmpty { null }
                easyTierError = payload.optString("last_error").ifEmpty { null }
                easyTierIp = payload.optString("virtual_ipv4").ifEmpty { null }
            }
        } else {
            easyTierState = null
            easyTierError = null
            easyTierIp = null
        }

        setStatus(homeStatus(), easyTierError)
        setVirtualIp(easyTierIp)
    }

    private suspend fun MainDesign.fetch() {
        setStatus(homeStatus(), easyTierError)
        setVirtualIp(easyTierIp)

        val state = withClash {
            queryTunnelState()
        }
        val providers = withClash {
            queryProviders()
        }

        setMode(state.mode)
        setHasProviders(providers.isNotEmpty())

        withProfile {
            setProfileName(queryActive()?.name)
        }
    }

    private suspend fun MainDesign.fetchTraffic() {
        withClash {
            setForwarded(queryTrafficTotal())
        }
    }

    private suspend fun MainDesign.startClash() {
        val active = withProfile { queryActive() }

        if (active == null || !active.imported) {
            showToast(DesignR.string.no_profile_selected, ToastDuration.Long) {
                setAction(DesignR.string.profiles) {
                    startActivity(ProfilesActivity::class.intent)
                }
            }

            return
        }

        val vpnRequest = startClashService()

        try {
            if (vpnRequest != null) {
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest
                )

                if (result.resultCode == RESULT_OK)
                    startClashService()
            }
        } catch (e: Exception) {
            design?.showToast(DesignR.string.unable_to_start_vpn, ToastDuration.Long)
        }
    }

    private suspend fun queryAppVersionName(): String {
        return withContext(Dispatchers.IO) {
            packageManager.getPackageInfo(packageName, 0).versionName + "\n" + Bridge.nativeCoreVersion().replace("_", "-")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requestPermissionLauncher =
                registerForActivityResult(RequestPermission()
                ) { isGranted: Boolean ->
                }
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setupShortcuts()
    }

    private fun setupShortcuts() {
        // Skip dynamic shortcut setup when the app icon is hidden.
        if (uiStore.hideAppIcon) return

        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
            Intent.FLAG_ACTIVITY_NO_ANIMATION

        val toggle = ShortcutInfoCompat.Builder(this, "toggle_clash")
            .setShortLabel(getString(DesignR.string.shortcut_toggle_short))
            .setLongLabel(getString(DesignR.string.shortcut_toggle_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_all))
            .setIntent(
                Intent(Intents.ACTION_TOGGLE_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(0)
            .build()

        val start = ShortcutInfoCompat.Builder(this, "start_clash")
            .setShortLabel(getString(DesignR.string.shortcut_start_short))
            .setLongLabel(getString(DesignR.string.shortcut_start_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_on))
            .setIntent(
                Intent(Intents.ACTION_START_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(1)
            .build()

        val stop = ShortcutInfoCompat.Builder(this, "stop_clash")
            .setShortLabel(getString(DesignR.string.shortcut_stop_short))
            .setLongLabel(getString(DesignR.string.shortcut_stop_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_off))
            .setIntent(
                Intent(Intents.ACTION_STOP_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(2)
            .build()

        ShortcutManagerCompat.setDynamicShortcuts(this, listOf(toggle, start, stop))
    }
}
