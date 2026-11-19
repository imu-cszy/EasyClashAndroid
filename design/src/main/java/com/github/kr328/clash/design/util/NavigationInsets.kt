package com.github.kr328.clash.design.util

import androidx.databinding.Observable
import com.github.kr328.clash.design.BR
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.Surface
import com.github.kr328.clash.design.view.BottomNavBar
import android.view.ViewGroup

/**
 * 让悬浮底部导航避开系统导航栏。
 *
 * 为什么不用 DataBinding 表达式：在 `<include>` 的 layout_margin 上写 `@{...}` 会让
 * design 模块的 kapt/DataBinding 直接失败（无错误信息）。这里用 Surface 的可观察 insets
 * 在代码里更新 margin，行为等价且不影响布局生成。
 */
fun BottomNavBar.applySurfaceInsets(surface: Surface) {
    val base = context.getPixels(R.dimen.ui_space_12)

    fun update() {
        val params = layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val target = base + surface.insets.bottom
        if (params.bottomMargin != target) {
            params.bottomMargin = target
            layoutParams = params
        }
    }

    surface.addOnPropertyChangedCallback(object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (propertyId == BR.insets) {
                update()
            }
        }
    })

    update()
}
