package com.github.kr328.clash.core.easytier

/**
 * EasyTier 原生调用统一异常。
 *
 * @param stage    调用阶段（parse/run/retain/collect）
 * @param detail   错误详情（native getLastError 或 Java 侧异常消息）
 */
class EasyTierException(
    val stage: Stage,
    detail: String?,
) : RuntimeException("EasyTier ${stage.name.lowercase()} failed: ${detail ?: "unknown error"}") {
    enum class Stage { LOAD, PARSE, RUN, RETAIN, COLLECT }
}
