package com.github.kr328.clash.design.util

import android.content.Context
import android.content.res.ColorStateList
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.design.R

/**
 * 日志等级配色。
 *
 * 翻新前 Info / Warning / Error 全部用 `colorControlActivated`，扫日志时看不出等级；
 * 这里按等级给出前景色与胶囊底色，全部取自 `ui_*` 令牌，深色模式同样达标。
 */
object LogLevelStyle {
    @JvmStatic
    fun label(level: LogMessage.Level): String = level.name.uppercase()

    @JvmStatic
    fun textColor(context: Context, level: LogMessage.Level): Int =
        context.resolveThemedColor(foregroundAttr(level))

    @JvmStatic
    fun chipTint(context: Context, level: LogMessage.Level): ColorStateList =
        ColorStateList.valueOf(context.resolveThemedColor(containerAttr(level)))

    private fun foregroundAttr(level: LogMessage.Level): Int = when (level) {
        LogMessage.Level.Info -> R.attr.ui_primary
        LogMessage.Level.Warning -> R.attr.ui_status_connecting
        LogMessage.Level.Error -> R.attr.ui_status_error
        LogMessage.Level.Debug -> R.attr.ui_on_surface_variant
        LogMessage.Level.Silent, LogMessage.Level.Unknown -> R.attr.ui_on_surface_variant
    }

    private fun containerAttr(level: LogMessage.Level): Int = when (level) {
        LogMessage.Level.Info -> R.attr.ui_primary_container
        LogMessage.Level.Warning -> R.attr.ui_status_connecting_container
        LogMessage.Level.Error -> R.attr.ui_status_error_container
        LogMessage.Level.Debug -> R.attr.ui_surface_container_high
        LogMessage.Level.Silent, LogMessage.Level.Unknown -> R.attr.ui_surface_container_high
    }
}
