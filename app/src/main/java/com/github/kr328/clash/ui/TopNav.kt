package com.github.kr328.clash.ui

import android.app.Activity
import android.content.Intent
import com.github.kr328.clash.MainActivity
import com.github.kr328.clash.ProfilesActivity
import com.github.kr328.clash.SettingsActivity
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.view.BottomNavBar

/**
 * 把底部导航绑到当前 Activity。
 *
 * 本项目是 Activity 架构（无 Fragment），所以 tab 切换 = 启动对应 Activity 并用
 * FLAG_ACTIVITY_REORDER_TO_FRONT 复用已有实例；不加转场动画以保持 tab 切换的“平级”感。
 */
fun Activity.bindBottomNav(nav: BottomNavBar, current: BottomNavBar.Destination) {
    nav.setSelected(current, animate = false)

    nav.onSelect = onSelect@{ destination ->
        if (destination == current) {
            return@onSelect
        }

        val target = when (destination) {
            BottomNavBar.Destination.Dashboard -> MainActivity::class.intent
            BottomNavBar.Destination.Profiles -> ProfilesActivity::class.intent
            BottomNavBar.Destination.Tools -> SettingsActivity::class.intent
        }

        startActivity(target.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
