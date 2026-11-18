package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.easytier.EasyTierConfig

/**
 * EasyTier 集成设置页（P4）。
 *
 * 字段即改即存（Activity 提供 [onConfigChange] 回调持久化并触发运行时刷新提示）。
 * 敏感字段（network-secret、两个 SOCKS5 密码）摘要只显示占位符，重新输入才会覆盖。
 */
class EasyTierSettingsDesign(
    context: Context,
    initial: EasyTierConfig,
    private val onConfigChange: (EasyTierConfig) -> Unit,
) : Design<EasyTierSettingsDesign.Request>(context) {
    enum class Request {
        ShowDiagnostics,
        RestartRequired,
    }

    private var config: EasyTierConfig = initial

    // ---- write-through 属性：每个字段 set 时更新 config 并回调 ----

    private var enabled: Boolean
        get() = config.enabled
        set(value) { config = config.copy(enabled = value); onConfigChange(config) }

    private var networkName: String?
        get() = config.networkName
        set(value) { config = config.copy(networkName = value.orEmpty()); onConfigChange(config) }

    private var networkSecret: String?
        get() = config.networkSecret
        set(value) {
            if (value != null && value != SECRET_PLACEHOLDER) {
                config = config.copy(networkSecret = value); onConfigChange(config)
            }
        }

    private var hostname: String?
        get() = config.hostname
        set(value) { config = config.copy(hostname = value.orEmpty()); onConfigChange(config) }

    private var ipv4: String?
        get() = config.ipv4
        set(value) { config = config.copy(ipv4 = value.orEmpty()); onConfigChange(config) }

    private var peers: List<String>?
        get() = config.peers
        set(value) { config = config.copy(peers = value.orEmpty()); onConfigChange(config) }

    private var socks5Port: Int?
        get() = config.socks5Port
        set(value) { config = config.copy(socks5Port = value ?: 0); onConfigChange(config) }

    private var lightsailHost: String?
        get() = config.lightsailHost
        set(value) { config = config.copy(lightsailHost = value.orEmpty()); onConfigChange(config) }

    private var normalPort: Int?
        get() = config.normalPort
        set(value) { config = config.copy(normalPort = value ?: 0); onConfigChange(config) }

    private var warpPort: Int?
        get() = config.warpPort
        set(value) { config = config.copy(warpPort = value ?: 0); onConfigChange(config) }

    private var normalUser: String?
        get() = config.normalUser
        set(value) { config = config.copy(normalUser = value.orEmpty()); onConfigChange(config) }

    private var normalPassword: String?
        get() = config.normalPassword
        set(value) {
            if (value != null && value != SECRET_PLACEHOLDER) {
                config = config.copy(normalPassword = value); onConfigChange(config)
            }
        }

    private var warpUser: String?
        get() = config.warpUser
        set(value) { config = config.copy(warpUser = value.orEmpty()); onConfigChange(config) }

    private var warpPassword: String?
        get() = config.warpPassword
        set(value) {
            if (value != null && value != SECRET_PLACEHOLDER) {
                config = config.copy(warpPassword = value); onConfigChange(config)
            }
        }

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    init {
        binding.surface = surface

        binding.activityBarLayout.applyFrom(context)

        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        // DSL lambda 的接收者是 PreferenceScreen，遮蔽 this；绑定属性引用必须在此处（this=Design）先提升为局部变量
        val ref_enabled = this::enabled
        val ref_networkName = this::networkName
        val ref_networkSecret = this::networkSecret
        val ref_hostname = this::hostname
        val ref_ipv4 = this::ipv4
        val ref_peers = this::peers
        val ref_socks5Port = this::socks5Port
        val ref_lightsailHost = this::lightsailHost
        val ref_normalPort = this::normalPort
        val ref_warpPort = this::warpPort
        val ref_normalUser = this::normalUser
        val ref_normalPassword = this::normalPassword
        val ref_warpUser = this::warpUser
        val ref_warpPassword = this::warpPassword

        val screen = preferenceScreen(context) {
            category(R.string.et_category_easytier)

            switch(
                value = ref_enabled,
                icon = R.drawable.ic_baseline_vpn_lock,
                title = R.string.et_enable,
                summary = R.string.et_enable_summary,
            ) {
                listener = OnChangedListener {
                    requests.trySend(Request.RestartRequired)
                }
            }

            editableText(
                value = ref_networkName,
                adapter = NullableTextAdapter.String,
                title = R.string.et_network_name,
                icon = R.drawable.ic_baseline_dns,
            )

            editableText(
                value = ref_networkSecret,
                adapter = secretAdapter,
                title = R.string.et_network_secret,
                icon = R.drawable.ic_baseline_key,
            )

            editableText(
                value = ref_hostname,
                adapter = NullableTextAdapter.String,
                title = R.string.et_hostname,
            )

            editableText(
                value = ref_ipv4,
                adapter = NullableTextAdapter.String,
                title = R.string.et_ipv4,
            )

            editableTextList(
                value = ref_peers,
                adapter = TextAdapter.String,
                title = R.string.et_peers,
                icon = R.drawable.ic_baseline_publish,
            )

            editableText(
                value = ref_socks5Port,
                adapter = NullableTextAdapter.Port,
                title = R.string.et_socks5_port,
            )

            category(R.string.et_category_lightsail)

            editableText(
                value = ref_lightsailHost,
                adapter = NullableTextAdapter.String,
                title = R.string.et_lightsail_host,
                icon = R.drawable.ic_baseline_domain,
            )

            editableText(
                value = ref_normalPort,
                adapter = NullableTextAdapter.Port,
                title = R.string.et_normal_port,
            )

            editableText(
                value = ref_warpPort,
                adapter = NullableTextAdapter.Port,
                title = R.string.et_warp_port,
            )

            editableText(
                value = ref_normalUser,
                adapter = NullableTextAdapter.String,
                title = R.string.et_normal_user,
            )

            editableText(
                value = ref_normalPassword,
                adapter = secretAdapter,
                title = R.string.et_normal_password,
                icon = R.drawable.ic_baseline_key,
            )

            editableText(
                value = ref_warpUser,
                adapter = NullableTextAdapter.String,
                title = R.string.et_warp_user,
            )

            editableText(
                value = ref_warpPassword,
                adapter = secretAdapter,
                title = R.string.et_warp_password,
                icon = R.drawable.ic_baseline_key,
            )

            category(R.string.et_category_diagnostics)

            clickable(
                icon = R.drawable.ic_outline_article,
                title = R.string.et_view_diagnostics,
            ) {
                requests.trySend(Request.ShowDiagnostics)
            }
        }

        binding.content.addView(screen.root)
    }

    companion object {
        private const val SECRET_PLACEHOLDER = "••••"

        /** 摘要显示占位符；占位符回传时不覆盖原值（setter 侧过滤） */
        private val secretAdapter = object : NullableTextAdapter<String?> {
            override fun from(value: String?): String? =
                if (value.isNullOrEmpty()) "" else SECRET_PLACEHOLDER

            override fun to(text: String?): String? = text
        }
    }
}
