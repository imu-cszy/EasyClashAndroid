package com.github.kr328.clash.service.easytier

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 环形缓冲诊断日志：按分类记录阶段化事件，导出自动脱敏。
 * 单进程内存态（:background），随 Service 生命周期存续。
 */
class Diagnostics(private val capacity: Int = 500) {
    enum class Category { NATIVE, CONFIG, RUNTIME, SOCKS5, MIHOMO }

    data class Entry(
        val time: Long,
        val category: Category,
        val stage: String,
        val message: String,
    )

    private val buffer = ArrayDeque<Entry>(capacity)

    fun log(category: Category, stage: String, message: String) {
        synchronized(buffer) {
            if (buffer.size >= capacity) {
                buffer.removeFirst()
            }
            buffer.addLast(Entry(System.currentTimeMillis(), category, stage, mask(message)))
        }
    }

    fun snapshot(): List<Entry> = synchronized(buffer) {
        buffer.toList()
    }

    fun clear() = synchronized(buffer) {
        buffer.clear()
    }

    /**
     * 导出脱敏文本（剪贴板/分享用）。
     */
    fun exportMasked(): String {
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
        return snapshot().joinToString("\n") { e ->
            "${fmt.format(Date(e.time))} ${e.category.name}/${e.stage}: ${mask(e.message)}"
        }
    }

    companion object {
        // 键名可带下划线/连字符前缀（如 network_secret、private_key），值取到下一个空白或引号串
        private val SECRET_PATTERNS = listOf(
            Regex("""(?i)([a-z0-9_-]*(?:secret|password|passwd|token|private[-_]?key)[a-z0-9_-]*)\s*[=:]\s*("[^"]*"|\S+)"""),
            Regex("""(?i)(network_secret|password|username)\s*=\s*"[^"]*""""),
        )
        private val URI_PORT = Regex("""([a-z0-9+.-]+)://([^/\s:"]+):(\d+)""", RegexOption.IGNORE_CASE)

        /** secret/password/token/private-key 值与 URI host:port 打码 */
        fun mask(text: String): String {
            var out = text
            SECRET_PATTERNS.forEach { re ->
                out = out.replace(re) { mr ->
                    val name = mr.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "secret"
                    "$name=***"
                }
            }
            out = out.replace(URI_PORT) { mr -> "${mr.groupValues[1]}://***:${mr.groupValues[3]}" }
            return out
        }
    }
}
