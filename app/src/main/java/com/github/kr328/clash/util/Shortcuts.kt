package com.github.kr328.clash.util

import android.app.Activity
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.github.kr328.clash.ExternalControlActivity
import com.github.kr328.clash.R
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.design.R as DesignR

/**
 * 桌面快捷方式。
 *
 * 长按图标弹出的动态快捷方式要点两次（长按 → 选择），这里提供"钉到桌面"的固定快捷方式：
 * 桌面上的单个图标，点一下即经 [ExternalControlActivity] 启停服务，不打开主界面。
 */
object Shortcuts {
    /** @return false 表示当前桌面不支持固定快捷方式 */
    fun requestPinnedToggle(activity: Activity): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(activity)) return false

        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
            Intent.FLAG_ACTIVITY_NO_ANIMATION

        val shortcut = ShortcutInfoCompat.Builder(activity, "pinned_toggle")
            .setShortLabel(activity.getString(DesignR.string.shortcut_toggle_short))
            .setLongLabel(activity.getString(DesignR.string.shortcut_toggle_long))
            .setIcon(IconCompat.createWithResource(activity, R.drawable.ic_toggle_all))
            .setIntent(
                Intent(Intents.ACTION_TOGGLE_CLASH)
                    .setClassName(activity, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .build()

        return ShortcutManagerCompat.requestPinShortcut(activity, shortcut, null)
    }
}
