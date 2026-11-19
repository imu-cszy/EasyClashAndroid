package com.github.kr328.clash.design

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.trafficTotal
import com.github.kr328.clash.design.databinding.DesignAboutBinding
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.util.applySurfaceInsets
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainDesign(context: Context) : Design<MainDesign.Request>(context) {
    enum class Request {
        ToggleStatus,
        OpenProxy,
        OpenProfiles,
        OpenProviders,
        OpenLogs,
        OpenSettings,
        OpenHelp,
        OpenAbout,
    }

    private val binding = DesignMainBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    /** EasyClash: 底部导航（由宿主 Activity 绑定跳转） */
    val bottomNav: com.github.kr328.clash.design.view.BottomNavBar
        get() = binding.bottomNav

    /** 提示条锚定到导航栏上方，避免被遮挡 */
    override val snackbarAnchor: View?
        get() = binding.bottomNav

    private var running = false
    private var forwardedText = ""
    private var modeText = ""

    init {
        binding.bottomNav.applySurfaceInsets(surface)
    }

    suspend fun setProfileName(name: String?) {
        withContext(Dispatchers.Main) {
            binding.profileName = name
        }
    }

    suspend fun setClashRunning(running: Boolean) {
        withContext(Dispatchers.Main) {
            this@MainDesign.running = running
            renderStatus()
        }
    }

    suspend fun setForwarded(value: Long) {
        withContext(Dispatchers.Main) {
            forwardedText = value.trafficTotal()
            renderStatus()
        }
    }

    suspend fun setMode(mode: TunnelState.Mode) {
        withContext(Dispatchers.Main) {
            modeText = when (mode) {
                TunnelState.Mode.Direct -> context.getString(R.string.direct_mode)
                TunnelState.Mode.Global -> context.getString(R.string.global_mode)
                TunnelState.Mode.Rule -> context.getString(R.string.rule_mode)
                else -> context.getString(R.string.rule_mode)
            }
            binding.mode = modeText
            renderStatus()
        }
    }

    suspend fun setHasProviders(has: Boolean) {
        withContext(Dispatchers.Main) {
            binding.hasProviders = has
        }
    }

    suspend fun showAbout(versionName: String) {
        withContext(Dispatchers.Main) {
            val binding = DesignAboutBinding.inflate(context.layoutInflater).apply {
                this.versionName = versionName
            }

            AlertDialog.Builder(context)
                .setView(binding.root)
                .show()
        }
    }

    /**
     * 状态卡渲染：chip 颜色/文案、大号数值、圆形按钮。
     * 颜色全部取自 `ui_*` 令牌，深色模式下同样达标（见 docs/research/ui/tokens.md）。
     */
    private fun renderStatus() {
        val chip = binding.statusChip
        chip.setText(if (running) R.string.running else R.string.stopped)
        chip.setTextColor(
            context.resolveThemedColor(
                if (running) R.attr.ui_status_connected else R.attr.ui_on_surface_variant
            )
        )
        chip.backgroundTintList = ColorStateList.valueOf(
            context.resolveThemedColor(
                if (running) R.attr.ui_status_connected_container else R.attr.ui_surface_container_high
            )
        )

        // 大号数值始终展示累计转发流量；是否运行由 chip 表达，避免与 chip 文案重复
        binding.statusValue.text = forwardedText.ifEmpty { "0 B" }

        binding.statusHint.text = if (running) {
            modeText.ifEmpty { context.getString(R.string.rule_mode) }
        } else {
            context.getString(R.string.tap_to_start)
        }

        binding.actionButton.backgroundTintList = ColorStateList.valueOf(
            context.resolveThemedColor(
                if (running) R.attr.ui_primary else R.attr.ui_surface_container_high
            )
        )
        binding.actionIcon.setImageResource(
            if (running) R.drawable.ic_stop_filled else R.drawable.ic_play_filled
        )
        binding.actionIcon.imageTintList = ColorStateList.valueOf(
            context.resolveThemedColor(
                if (running) R.attr.ui_on_primary else R.attr.ui_on_surface
            )
        )
    }

    init {
        binding.self = this

        renderStatus()
    }

    fun request(request: Request) {
        requests.trySend(request)
    }
}
