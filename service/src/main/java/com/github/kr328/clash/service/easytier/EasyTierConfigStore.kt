package com.github.kr328.clash.service.easytier

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.service.PreferenceProvider

/**
 * EasyTier 配置持久化（非敏感字段，ServiceStore 同款模式，跨进程可读）。
 *
 * 敏感字段（network-secret、两个 SOCKS5 密码）不在此处：
 * UI 进程写入时调用 [SecretStore.encrypt]，运行时在 :background 进程解密拼装。
 */
class EasyTierConfigStore(context: Context) {
    private val store = Store(
        PreferenceProvider
            .createSharedPreferencesFromContext(context)
            .asStoreProvider()
    )

    var enabled by store.boolean(key = "easytier_enabled", defaultValue = false)
    var instanceName by store.string(key = "easytier_instance_name", defaultValue = EasyTierConfig.DEFAULT_INSTANCE_NAME)
    var networkName by store.string(key = "easytier_network_name", defaultValue = "")
    var hostname by store.string(key = "easytier_hostname", defaultValue = EasyTierConfig.DEFAULT_INSTANCE_NAME)
    var ipv4 by store.string(key = "easytier_ipv4", defaultValue = EasyTierConfig.DEFAULT_IPV4)
    var peers by store.stringSet(key = "easytier_peers", defaultValue = emptySet())
    var socks5Port by store.int(key = "easytier_socks5_port", defaultValue = EasyTierConfig.DEFAULT_SOCKS5_PORT)
    var defaultProtocol by store.string(key = "easytier_default_protocol", defaultValue = "tcp")
    var latencyFirst by store.boolean(key = "easytier_latency_first", defaultValue = false)
    var lightsailHost by store.string(key = "easytier_lightsail_host", defaultValue = EasyTierConfig.DEFAULT_LIGHTSAIL_HOST)
    var normalPort by store.int(key = "easytier_normal_port", defaultValue = 1080)
    var warpPort by store.int(key = "easytier_warp_port", defaultValue = 1081)
    var normalUser by store.string(key = "easytier_normal_user", defaultValue = "")
    var warpUser by store.string(key = "easytier_warp_user", defaultValue = "")

    /**
     * 读取完整配置（含解密后的敏感字段）。解密失败的字段置空（UI 侧提示重填）。
     */
    fun load(context: Context): EasyTierConfig {
        return EasyTierConfig(
            enabled = enabled,
            instanceName = instanceName,
            networkName = networkName,
            networkSecret = SecretStore.decrypt(context, SecretStore.KEY_NETWORK_SECRET) ?: "",
            hostname = hostname,
            ipv4 = ipv4,
            peers = peers.toList(),
            socks5Port = socks5Port,
            defaultProtocol = defaultProtocol,
            latencyFirst = latencyFirst,
            lightsailHost = lightsailHost,
            normalPort = normalPort,
            warpPort = warpPort,
            normalUser = normalUser,
            warpUser = warpUser,
            normalPassword = SecretStore.decrypt(context, SecretStore.KEY_NORMAL_PASS) ?: "",
            warpPassword = SecretStore.decrypt(context, SecretStore.KEY_WARP_PASS) ?: "",
        )
    }

    /**
     * 保存非敏感字段并加密敏感字段。
     */
    fun save(context: Context, config: EasyTierConfig) {
        enabled = config.enabled
        instanceName = config.instanceName
        networkName = config.networkName
        hostname = config.hostname
        ipv4 = config.ipv4
        peers = config.peers.toSet()
        socks5Port = config.socks5Port
        defaultProtocol = config.defaultProtocol
        latencyFirst = config.latencyFirst
        lightsailHost = config.lightsailHost
        normalPort = config.normalPort
        warpPort = config.warpPort
        normalUser = config.normalUser
        warpUser = config.warpUser
        SecretStore.encrypt(context, SecretStore.KEY_NETWORK_SECRET, config.networkSecret)
        SecretStore.encrypt(context, SecretStore.KEY_NORMAL_PASS, config.normalPassword)
        SecretStore.encrypt(context, SecretStore.KEY_WARP_PASS, config.warpPassword)
    }
}
