package com.github.kr328.clash.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.content.ContextCompat
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
     * 蓝底 + 停止符 = 正在运行；灰底 + 播放符 = 已停止。
     * 用位图而不是资源矢量，是因为矢量无法按状态着色，而且桌面图标的尺寸由启动器决定。
     */
    private fun toggleIcon(context: Context, running: Boolean): IconCompat {
        val density = context.resources.displayMetrics.density
        val size = (108 * density).toInt().coerceAtLeast(96)

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (running) 0xFF0A84FF.toInt() else 0xFFB9BDC6.toInt()
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, background)

        val glyph = ContextCompat.getDrawable(
            context,
            if (running) DesignR.drawable.ic_stop_filled else DesignR.drawable.ic_play_filled,
        )?.mutate()

        if (glyph != null) {
            glyph.setTint(Color.WHITE)
            val inset = (size * 0.27f).toInt()
            glyph.bounds = Rect(inset, inset, size - inset, size - inset)
            glyph.draw(canvas)
        }

        return IconCompat.createWithBitmap(bitmap)
    }
}
