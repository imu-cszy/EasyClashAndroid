package com.github.kr328.clash.design.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.resolveThemedColor

/**
 * FlClash 风格的悬浮底部导航（胶囊容器 + 滑动选中块）。
 *
 * 只负责展示与回调；页面跳转由宿主 Activity 决定（本项目是 Activity 架构，无 Fragment）。
 */
class BottomNavBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class Destination { Dashboard, Profiles, Tools }

    /** 用户点击了另一个 tab（不会在重复点击当前 tab 时触发） */
    var onSelect: ((Destination) -> Unit)? = null

    private val pill: View
    private val cells: List<View>
    private val icons: List<ImageView>
    private val labels: List<TextView>

    private var selected = Destination.Dashboard
    private var laidOut = false

    init {
        val pad = (6 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)

        inflate(context, R.layout.view_bottom_nav, this)

        pill = findViewById(R.id.nav_pill)
        cells = listOf(
            findViewById(R.id.nav_item_dashboard),
            findViewById(R.id.nav_item_profiles),
            findViewById(R.id.nav_item_tools),
        )
        icons = listOf(
            findViewById(R.id.nav_icon_dashboard),
            findViewById(R.id.nav_icon_profiles),
            findViewById(R.id.nav_icon_tools),
        )
        labels = listOf(
            findViewById(R.id.nav_label_dashboard),
            findViewById(R.id.nav_label_profiles),
            findViewById(R.id.nav_label_tools),
        )

        cells.forEachIndexed { index, cell ->
            cell.setOnClickListener { select(Destination.values()[index]) }
        }

        applyTint()
    }

    fun setSelected(destination: Destination, animate: Boolean = false) {
        selected = destination
        applyTint()

        if (laidOut) {
            positionPill(animate)
        } else {
            post { positionPill(false) }
        }
    }

    fun currentDestination(): Destination = selected

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        laidOut = true
        positionPill(false)
    }

    private fun select(destination: Destination) {
        if (destination == selected) {
            return
        }

        // 不做“乐观选中”：每个 tab 是独立 Activity，若在这里改本页选中态，
        // 返回本页时高亮会停留在别人身上。目标 Activity 创建时会绑定正确的 tab。
        onSelect?.invoke(destination)
    }

    private fun applyTint() {
        val active = context.resolveThemedColor(R.attr.ui_primary)
        val inactive = context.resolveThemedColor(R.attr.ui_on_surface_variant)

        Destination.values().forEachIndexed { index, destination ->
            val isActive = destination == selected
            val color = if (isActive) active else inactive

            icons[index].imageTintList = ColorStateList.valueOf(color)
            labels[index].setTextColor(color)
            labels[index].typeface =
                if (isActive) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        }
    }

    private fun positionPill(animate: Boolean) {
        val cell = cells.getOrNull(selected.ordinal) ?: return
        val width = cell.width
        if (width <= 0) {
            return
        }

        // 三个 cell 等宽，宽度只需在尺寸变化时更新一次（避免布局抖动取消动画）
        if (pill.layoutParams.width != width) {
            pill.layoutParams = pill.layoutParams.also { it.width = width }
        }

        val targetX = cell.left.toFloat()
        if (animate) {
            pill.animate()
                .x(targetX)
                .setDuration(200L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            pill.animate().cancel()
            pill.x = targetX
        }
    }
}
