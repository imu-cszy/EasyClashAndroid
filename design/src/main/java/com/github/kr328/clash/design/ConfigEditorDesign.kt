package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.databinding.DesignConfigEditorBinding
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

/**
 * 配置文件编辑器。
 *
 * 只负责展示与请求；读写/校验/落盘都在 Activity 中完成（校验需在工作线程调用 mihomo）。
 */
class ConfigEditorDesign(context: Context) : Design<ConfigEditorDesign.Request>(context) {
    enum class Request {
        Validate, Save
    }

    private val binding = DesignConfigEditorBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    /** 编辑器当前文本 */
    var content: String
        get() = binding.editor.text?.toString().orEmpty()
        set(value) {
            binding.editor.setText(value)
            binding.editor.setSelection(0)
        }

    /** 是否允许编辑（超大文件降级为只读） */
    var editable: Boolean
        get() = binding.editor.isEnabled
        set(value) {
            binding.editor.isEnabled = value
        }

    fun requestValidate() {
        requests.trySend(Request.Validate)
    }

    fun requestSave() {
        requests.trySend(Request.Save)
    }

    /** 底部状态行：null/空 = 隐藏 */
    fun setStatus(text: CharSequence?, isError: Boolean = false) {
        binding.status.text = text
        binding.status.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        binding.status.setTextColor(
            if (isError) 0xFFE53935.toInt() else 0xFF43A047.toInt()
        )
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)
    }
}
