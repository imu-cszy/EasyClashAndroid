package com.github.kr328.clash.util

import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.github.kr328.clash.design.R
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 配置包导出/导入的简易输入框。
 *
 * 项目 UI 采用 Activity + Design 模式，但这两个弹窗只在 ProfilesActivity 内部使用，
 * 用系统 AlertDialog 实现可避免为一个输入框新增完整 Design 页面。
 */
object BundleDialogs {
    /** 导出选择：password 为 null 表示明文导出 */
    data class ExportChoice(val password: String?)

    /**
     * 导出对话框：是否加密 + 密码（两次输入）。
     * @return null 表示取消
     */
    suspend fun requestExportChoice(context: Context): ExportChoice? =
        suspendCancellableCoroutine { cont ->
            var resumed = false

            val padding = (20 * context.resources.displayMetrics.density).toInt()
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding / 2, padding, 0)
            }

            val hintView = TextView(context).apply {
                text = context.getString(R.string.bundle_export_hint)
                setTextColor(0xFF757575.toInt())
                textSize = 12f
            }

            val usePassword = CheckBox(context).apply {
                text = context.getString(R.string.bundle_use_password)
                isChecked = true
            }

            val password = EditText(context).apply {
                this.hint = context.getString(R.string.bundle_password)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                visibility = android.view.View.VISIBLE
            }

            val confirm = EditText(context).apply {
                this.hint = context.getString(R.string.bundle_password_confirm)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                visibility = android.view.View.VISIBLE
            }

            usePassword.setOnCheckedChangeListener { _, checked ->
                password.visibility = if (checked) android.view.View.VISIBLE else android.view.View.GONE
                confirm.visibility = if (checked) android.view.View.VISIBLE else android.view.View.GONE
            }

            root.addView(hintView)
            root.addView(usePassword)
            root.addView(password, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(confirm, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val dialog = android.app.AlertDialog.Builder(context)
                .setTitle(R.string.bundle_export)
                .setView(root)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    if (!resumed) {
                        resumed = true
                        cont.resume(null)
                    }
                }
                .setOnCancelListener {
                    if (!resumed) {
                        resumed = true
                        cont.resume(null)
                    }
                }
                .create()

            dialog.setOnShowListener {
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (!usePassword.isChecked) {
                        if (!resumed) {
                            resumed = true
                            cont.resume(ExportChoice(null))
                        }
                        dialog.dismiss()
                        return@setOnClickListener
                    }

                    val pwd = password.text?.toString().orEmpty()
                    val pwd2 = confirm.text?.toString().orEmpty()
                    when {
                        pwd.length < 6 -> password.error = context.getString(R.string.bundle_password_too_short)
                        pwd != pwd2 -> confirm.error = context.getString(R.string.bundle_password_mismatch)
                        else -> {
                            if (!resumed) {
                                resumed = true
                                cont.resume(ExportChoice(pwd))
                            }
                            dialog.dismiss()
                        }
                    }
                }
            }

            dialog.show()
            cont.invokeOnCancellation { dialog.dismiss() }
        }

    /**
     * 单个密码输入（导入加密包时使用）。
     * @return null 表示取消
     */
    suspend fun requestPassword(context: Context, title: Int): String? =
        suspendCancellableCoroutine { cont ->
            var resumed = false

            val padding = (20 * context.resources.displayMetrics.density).toInt()
            val input = EditText(context).apply {
                this.hint = context.getString(R.string.bundle_password)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setPadding(padding, padding / 4, padding, 0)
            }

            val dialog = android.app.AlertDialog.Builder(context)
                .setTitle(title)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    if (!resumed) {
                        resumed = true
                        cont.resume(input.text?.toString().orEmpty())
                    }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    if (!resumed) {
                        resumed = true
                        cont.resume(null)
                    }
                }
                .setOnCancelListener {
                    if (!resumed) {
                        resumed = true
                        cont.resume(null)
                    }
                }
                .create()

            dialog.show()
            cont.invokeOnCancellation { dialog.dismiss() }
        }
}
