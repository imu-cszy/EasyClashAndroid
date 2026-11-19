package com.github.kr328.clash.design

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
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
    /** 首页状态卡的五个状态（由 VPN 运行状态 + EasyTier 状态归并而来） */
    enum class Status {
        Stopped,
        Connecting,
        Connected,
        Degraded,
        Error,
    }

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

    private var status = Status.Stopped
    private var statusDetail: String? = null
    private var virtualIp: String? = null
    /** 目标累计流量与当前显示值：数值变化时滚动过去，避免每秒硬跳 */
    private var forwardedBytes = 0L
    private var displayedBytes = 0L
    private var valueAnimator: ValueAnimator? = null
    private var modeText = ""

    /** "连接中" 时 chip 的呼吸动画 */
    private val pulse by lazy {
        ObjectAnimator.ofFloat(binding.statusChip, View.ALPHA, 1f, 0.55f).apply {
            duration = 900
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
    }

    init {
        binding.bottomNav.applySurfaceInsets(surface)
    }

    suspend fun setProfileName(name: String?) {
        withContext(Dispatchers.Main) {
            binding.profileName = name
        }
    }

    suspend fun setStatus(status: Status, detail: String? = null) {
        withContext(Dispatchers.Main) {
            this@MainDesign.status = status
            this@MainDesign.statusDetail = detail
            renderStatus()
        }
    }

    suspend fun setVirtualIp(ip: String?) {
        withContext(Dispatchers.Main) {
            virtualIp = ip
            renderStatus()
        }
    }

    suspend fun setForwarded(value: Long) {
        withContext(Dispatchers.Main) {
            animateForwardedTo(value)
        }
    }

    private fun animateForwardedTo(target: Long) {
        if (target == forwardedBytes) return

        val start = displayedBytes
        forwardedBytes = target

        valueAnimator?.cancel()

        if (target < start) {
            // 服务重启后归零：直接跳，不做"倒着滚"
            displayedBytes = target
            renderStatus()
            return
        }

        valueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                displayedBytes = start + ((target - start) * fraction).toLong()
                binding.statusValue.text =
            if (displayedBytes == 0L) "0 B" else displayedBytes.trafficTotal()
            }
            start()
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
     * 状态卡渲染：chip 文案/配色、大号数值、圆形按钮。
     * 五态颜色全部取自 `ui_status_*` 令牌，深色模式下同样达标
     * （见 docs/research/ui/tokens.md）。
     */
    private fun renderStatus() {
        val chip = binding.statusChip
        val idle = status == Status.Stopped

        chip.setText(
            when (status) {
                Status.Stopped -> R.string.stopped
                Status.Connecting -> R.string.connecting
                Status.Connected -> R.string.running
                Status.Degraded -> R.string.degraded
                Status.Error -> R.string.status_error
            }
        )
        chip.setTextColor(context.resolveThemedColor(chipForegroundAttr()))
        chip.backgroundTintList =
            ColorStateList.valueOf(context.resolveThemedColor(chipContainerAttr()))

        // 连接中：chip 轻微呼吸，让"正在做事"有反馈；其余状态保持静止
        if (status == Status.Connecting) {
            if (!pulse.isStarted) pulse.start()
        } else if (pulse.isStarted) {
            pulse.cancel()
            chip.alpha = 1f
        }

        // 大号数值始终展示累计转发流量；是否运行由 chip 表达，避免与 chip 文案重复
        binding.statusValue.text =
            if (displayedBytes == 0L) "0 B" else displayedBytes.trafficTotal()

        binding.statusHint.text = when (status) {
            Status.Stopped -> context.getString(R.string.tap_to_start)
            Status.Connecting -> statusDetail
                ?: context.getString(R.string.establishing_tunnel)
            Status.Connected -> listOfNotNull(virtualIp, modeText.ifEmpty {
                context.getString(R.string.rule_mode)
            }).joinToString(" · ")
            Status.Degraded -> statusDetail ?: context.getString(R.string.degraded)
            Status.Error -> statusDetail?.lineSequence()?.firstOrNull()?.take(48)
                ?: context.getString(R.string.status_error)
        }

        binding.actionButton.backgroundTintList = ColorStateList.valueOf(
            context.resolveThemedColor(
                if (idle || status == Status.Error) R.attr.ui_surface_container_high
                else R.attr.ui_primary
            )
        )
        binding.actionIcon.setImageResource(
            if (idle || status == Status.Error) R.drawable.ic_play_filled
            else R.drawable.ic_stop_filled
        )
        binding.actionIcon.imageTintList = ColorStateList.valueOf(
            context.resolveThemedColor(
                if (idle || status == Status.Error) R.attr.ui_on_surface else R.attr.ui_on_primary
            )
        )
    }

    private fun chipForegroundAttr(): Int = when (status) {
        Status.Stopped -> R.attr.ui_status_stopped
        Status.Connecting -> R.attr.ui_status_connecting
        Status.Connected -> R.attr.ui_status_connected
        Status.Degraded -> R.attr.ui_status_degraded
        Status.Error -> R.attr.ui_status_error
    }

    private fun chipContainerAttr(): Int = when (status) {
        Status.Stopped -> R.attr.ui_surface_container_high
        Status.Connecting -> R.attr.ui_status_connecting_container
        Status.Connected -> R.attr.ui_status_connected_container
        Status.Degraded -> R.attr.ui_status_degraded_container
        Status.Error -> R.attr.ui_status_error_container
    }

    init {
        binding.self = this

        renderStatus()
    }

    fun request(request: Request) {
        requests.trySend(request)
    }
}
