package com.github.kr328.clash

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.design.ConfigEditorDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendProfileChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 应用内编辑 profile 的 config.yaml。
 *
 * 校验与保存共用同一路径：备份 → 原子写 → mihomo 校验（UnmarshalAndPatch + Parse）。
 * 校验失败自动回滚，绝不留下坏配置；成功后可选择是否触发服务重载。
 */
class ConfigEditorActivity : BaseActivity<ConfigEditorDesign>() {
    private companion object {
        /** 超过 2MB 不再允许编辑：EditText 处理大文本会明显卡顿 */
        const val MAX_EDITABLE_BYTES = 2 * 1024 * 1024L
        const val CONFIG_NAME = "config.yaml"
        const val BACKUP_NAME = "config.yaml.bak"
    }

    override suspend fun main() {
        val uuid = intent.uuid ?: return finish()

        val dir = importedDir.resolve(uuid.toString())
        val file = dir.resolve(CONFIG_NAME)

        if (!file.exists()) {
            Log.w("Config editor: ${file.absolutePath} not found")
            finish()
            return
        }

        val editable = file.length() <= MAX_EDITABLE_BYTES
        val initial = withContext(Dispatchers.IO) {
            runCatching { file.readText() }.getOrElse { error ->
                Log.w("Config editor: read failed: ${error.message}")
                ""
            }
        }

        val design = ConfigEditorDesign(this)
        design.content = initial
        design.editable = editable
        if (!editable) {
            design.setStatus(getString(R.string.et_editor_too_large), isError = true)
        }

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive { }
                design.requests.onReceive { request ->
                    when (request) {
                        ConfigEditorDesign.Request.Validate -> {
                            design.setStatus(getString(R.string.et_editor_validating))
                            val error = withContext(Dispatchers.IO) {
                                writeAndValidate(dir, file, design.content)
                            }
                            design.setStatus(
                                error ?: getString(R.string.et_editor_valid_ok),
                                isError = error != null,
                            )
                        }

                        ConfigEditorDesign.Request.Save -> {
                            if (!editable) {
                                design.setStatus(getString(R.string.et_editor_too_large), isError = true)
                            } else {
                                design.setStatus(getString(R.string.et_editor_saving))
                                val error = withContext(Dispatchers.IO) {
                                    writeAndValidate(dir, file, design.content)
                                }
                                if (error == null) {
                                    design.setStatus(getString(R.string.et_editor_saved_ok))
                                    design.showToast(
                                        getString(R.string.et_editor_saved_ok),
                                        ToastDuration.Short,
                                    )
                                    // 触发服务重载（仅当前激活的 profile 会真正重新加载）
                                    sendProfileChanged(uuid)
                                } else {
                                    design.setStatus(error, isError = true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 备份 → 原子写入 → 校验；失败时回滚并返回错误文本，成功返回 null。
     */
    private fun writeAndValidate(dir: File, file: File, text: String): String? {
        val backup = File(dir, BACKUP_NAME)
        val temp = File(dir, "$CONFIG_NAME.tmp")

        return try {
            file.copyTo(backup, overwrite = true)

            temp.writeText(text)
            if (!temp.renameTo(file)) {
                temp.delete()
                return "无法写入配置文件"
            }

            val error = runCatching { Clash.validateProfile(dir) }.getOrElse { it.message ?: "校验失败" }
            if (error != null) {
                // 回滚到编辑前的内容
                runCatching { backup.copyTo(file, overwrite = true) }
                temp.delete()
                error
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w("Config editor: write failed: ${e.message}")
            runCatching { backup.copyTo(file, overwrite = true) }
            temp.delete()
            e.message ?: "写入失败"
        }
    }
}
