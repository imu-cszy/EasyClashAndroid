package com.github.kr328.clash.core.easytier

import android.os.Looper
import com.easytier.jni.EasyTierJNI
import com.github.kr328.clash.common.log.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * EasyTier 原生桥接唯一入口。
 *
 * 规则（见 docs/ARCHITECTURE.md §3.1）：
 * - 业务层禁止直接调用 com.easytier.jni.EasyTierJNI；
 * - 所有调用必须在工作线程（主线程调用直接抛错，防 ANR）；
 * - JNI 失败统一转为 [EasyTierException]，附带 native last-error。
 *
 * 加载顺序：先 easytier_ffi（C API 符号提供方），再 easytier_android_jni（JNI 符号）。
 */
object EasyTierNativeBridge {
    private val loaded = AtomicBoolean(false)

    sealed class LoadResult {
        object Ok : LoadResult()
        data class Failed(val error: String) : LoadResult()
    }

    /**
     * 幂等加载两个原生库。必须在工作线程调用。
     */
    fun ensureLoaded(): LoadResult {
        requireOffMainThread()
        if (loaded.get()) {
            return LoadResult.Ok
        }
        return try {
            // libeasytier_android_jni.so 引用 libeasytier_ffi.so 导出的 C 符号，
            // 先显式加载 FFI 库保证 dlopen 时符号可解析。
            System.loadLibrary("easytier_ffi")
            System.loadLibrary("easytier_android_jni")
            loaded.set(true)
            Log.i("EasyTier native libraries loaded")
            LoadResult.Ok
        } catch (e: Throwable) {
            LoadResult.Failed(e.message ?: e.javaClass.name)
        }
    }

    /**
     * 校验 TOML 配置。失败抛 [EasyTierException]（PARSE）。
     */
    fun parseConfig(toml: String) {
        val r = call(EasyTierException.Stage.PARSE) { EasyTierJNI.parseConfig(toml) }
        if (r != 0) throw EasyTierException(EasyTierException.Stage.PARSE, lastError())
    }

    /**
     * 启动（或以新配置替换）网络实例。失败抛 [EasyTierException]（RUN）。
     */
    fun runNetworkInstance(toml: String) {
        val r = call(EasyTierException.Stage.RUN) { EasyTierJNI.runNetworkInstance(toml) }
        if (r != 0) throw EasyTierException(EasyTierException.Stage.RUN, lastError())
    }

    /**
     * 保留指定实例并停止其余；无参数 = 停止全部。
     */
    fun retain(vararg instanceNames: String) {
        val names = instanceNames.toList().toTypedArray()
        val r = call(EasyTierException.Stage.RETAIN) { EasyTierJNI.retainNetworkInstance(names) }
        if (r != 0) throw EasyTierException(EasyTierException.Stage.RETAIN, lastError())
    }

    fun stopAll() {
        val r = call(EasyTierException.Stage.RETAIN) { EasyTierJNI.stopAllInstances() }
        if (r != 0) throw EasyTierException(EasyTierException.Stage.RETAIN, lastError())
    }

    /**
     * 收集网络信息并解析为模型。无运行实例时返回空 map。
     */
    fun collectNetworkInfos(): NetworkInstanceRunningInfoMap {
        val raw = call(EasyTierException.Stage.COLLECT) { EasyTierJNI.collectNetworkInfos(100) }
            ?: throw EasyTierException(EasyTierException.Stage.COLLECT, lastError() ?: "null result")
        return try {
            EasyTierJson.json.decodeFromString(NetworkInstanceRunningInfoMap.serializer(), raw)
        } catch (e: Exception) {
            throw EasyTierException(EasyTierException.Stage.COLLECT, "decode failed: ${e.message}")
        }
    }

    fun lastError(): String? = try {
        EasyTierJNI.getLastError()
    } catch (e: Throwable) {
        null
    }

    private fun requireOffMainThread() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw IllegalStateException("EasyTier JNI calls are not allowed on the main thread")
        }
    }

    private inline fun <T> call(stage: EasyTierException.Stage, block: () -> T): T {
        requireOffMainThread()
        when (val result = ensureLoaded()) {
            is LoadResult.Failed -> throw EasyTierException(EasyTierException.Stage.LOAD, result.error)
            LoadResult.Ok -> {}
        }
        return try {
            block()
        } catch (e: EasyTierException) {
            throw e
        } catch (e: Throwable) {
            throw EasyTierException(stage, e.message ?: e.javaClass.name)
        }
    }
}
