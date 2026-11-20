package com.github.kr328.clash.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.github.kr328.clash.ExternalControlActivity
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.design.R as DesignR

/**
 * 桌面快捷方式。
 *
 * 长按图标弹出的动态快捷方式要点两次（长按 → 选择），这里提供"钉到桌面"的固定快捷方式：
 * 桌面上的单个图标，点一下即经 [ExternalControlActivity] 启停服务，不打开主界面。
 *
 * 图标本身反映当前状态：运行中＝蓝底停止符，已停止＝灰底播放符；文案同步
 * 「已开启 / 已关闭」。状态每次变化（应用内启停、点图标启停、回到主界面）都会刷新。
 */
object Shortcuts {
    private const val PINNED_TOGGLE_ID = "pinned_toggle"

    /** @return false 表示当前桌面不支持固定快捷方式 */
    fun requestPinnedToggle(activity: Activity, running: Boolean): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(activity)) return false

        return ShortcutManagerCompat.requestPinShortcut(
            activity,
            buildToggleShortcut(activity, running),
            null,
        )
    }

    /** 把已钉到桌面的图标刷新成当前状态；未钉过则什么也不会发生。 */
    fun updateToggleShortcut(context: Context, running: Boolean) {
        runCatching {
            ShortcutManagerCompat.updateShortcuts(
                context,
                listOf(buildToggleShortcut(context, running)),
            )
        }
    }

    private fun buildToggleShortcut(context: Context, running: Boolean): ShortcutInfoCompat {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
            Intent.FLAG_ACTIVITY_NO_ANIMATION

        return ShortcutInfoCompat.Builder(context, PINNED_TOGGLE_ID)
            .setShortLabel(
                context.getString(
                    if (running) DesignR.string.shortcut_state_on_short
                    else DesignR.string.shortcut_state_off_short
                )
            )
            .setLongLabel(
                context.getString(
                    if (running) DesignR.string.shortcut_state_on_long
                    else DesignR.string.shortcut_state_off_long
                )
            )
            .setIcon(toggleIcon(context, running))
            .setIntent(
                Intent(Intents.ACTION_TOGGLE_CLASH)
                    .setClassName(context, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .build()
    }

    /**
     * 开关样式两态图：已开启 = 蓝色轨道、旋钮在右；已关闭 = 灰色轨道、旋钮在左。
     * 用运行时绘制的位图：矢量无法按状态着色，桌面图标的实际尺寸也由启动器决定。
     */
    private fun toggleIcon(context: Context, running: Boolean): IconCompat {
        val density = context.resources.displayMetrics.density
        val size = (108 * density).toInt().coerceAtLeast(96)

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val trackW = size * 0.62f
        val trackH = size * 0.36f
        val left = (size - trackW) / 2f
        val top = (size - trackH) / 2f
        val radius = trackH / 2f

        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (running) 0xFF0A84FF.toInt() else 0xFFB0B3BA.toInt()
        }
        canvas.drawRoundRect(left, top, left + trackW, top + trackH, radius, radius, track)

        // 旋钮：直径略小于轨道高，左右各留轨道高的 8% 边距；开启时滑到右侧
        val knobDiameter = trackH * 0.84f
        val margin = trackH * 0.08f
        val knobCenterX = if (running) {
            left + trackW - margin - knobDiameter / 2f
        } else {
            left + margin + knobDiameter / 2f
        }

        val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawCircle(knobCenterX, top + trackH / 2f, knobDiameter / 2f, knob)

        return IconCompat.createWithBitmap(bitmap)
    }
}
