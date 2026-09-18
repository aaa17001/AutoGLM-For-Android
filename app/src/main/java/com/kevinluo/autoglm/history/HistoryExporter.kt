package com.kevinluo.autoglm.history

import android.content.Context
import com.kevinluo.autoglm.task.RuntimeInstructionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports a complete task history without leaking app-private absolute file paths.
 */
class HistoryExporter(private val context: Context) {
    private val timestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    private val displayTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    suspend fun exportMarkdown(task: TaskHistory): File = withContext(Dispatchers.IO) {
        val output = createOutputFile(task, "md")
        output.writeText(renderMarkdown(task))
        output
    }

    suspend fun exportJson(task: TaskHistory): File = withContext(Dispatchers.IO) {
        val output = createOutputFile(task, "json")
        output.writeText(renderJson(task).toString(2))
        output
    }

    suspend fun exportZip(task: TaskHistory): File = withContext(Dispatchers.IO) {
        val output = createOutputFile(task, "zip")

        ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zip ->
            writeTextEntry(zip, "history.md", renderMarkdown(task))
            writeTextEntry(zip, "history.json", renderJson(task).toString(2))

            task.steps.forEach { step ->
                addScreenshotToZip(
                    zip = zip,
                    sourcePath = step.screenshotPath,
                    entryName = screenshotRelativePath(step, annotated = false),
                )
                addScreenshotToZip(
                    zip = zip,
                    sourcePath = step.annotatedScreenshotPath,
                    entryName = screenshotRelativePath(step, annotated = true),
                )
            }
        }

        output
    }

    fun mimeTypeForExtension(extension: String): String =
        when (extension.lowercase()) {
            "md" -> "text/markdown"
            "json" -> "application/json"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }

    private fun createOutputFile(
        task: TaskHistory,
        extension: String,
    ): File {
        val exportDir = File(context.cacheDir, "export").also { it.mkdirs() }
        val timestamp = timestampFormat.format(Date(task.startTime))
        val fileName = "AutoGLM_Task_${timestamp}_${task.id.take(8)}.$extension"
        return File(exportDir, fileName)
    }

    private fun renderMarkdown(task: TaskHistory): String =
        buildString {
            appendLine("# AutoGLM 完整任务记录")
            appendLine()
            appendLine("**原始任务：** ${task.taskDescription}")
            appendLine()
            appendLine("- 开始时间：${displayTimeFormat.format(Date(task.startTime))}")
            task.endTime?.let {
                appendLine("- 结束时间：${displayTimeFormat.format(Date(it))}")
            }
            appendLine("- 结果：${if (task.success) "成功" else "失败"}")
            appendLine("- Agent 步骤：${task.stepCount}")
            appendLine("- 执行轮数：${task.roundCount}")
            appendLine("- 人工追加：${task.instructionCount}")
            task.completionMessage?.takeIf { it.isNotBlank() }?.let {
                appendLine("- 完成信息：$it")
            }

            task.repeatConfig?.let { config ->
                appendLine(
                    "- 循环配置：" +
                        if (config.enabled) {
                            "${config.minMinutes}~${config.maxMinutes} 分钟（每轮重新随机到秒）"
                        } else {
                            "关闭"
                        },
                )
            }
            appendLine()

            buildHistoryTimeline(task)
                .drop(1)
                .forEach { item ->
                    when (item) {
                        is HistoryTimelineItem.Header -> Unit

                        is HistoryTimelineItem.RoundHeader -> {
                            appendLine("---")
                            appendLine()
                            appendLine("## 第 ${item.roundNumber} 轮")
                            appendLine()
                        }

                        is HistoryTimelineItem.AgentStepItem -> {
                            val step = item.step
                            appendLine("### 第 ${step.roundNumber} 轮 / Step ${step.stepNumber}")
                            appendLine()
                            appendLine("- 时间：${displayTimeFormat.format(Date(step.timestamp))}")
                            appendLine("- 状态：${if (step.success) "成功" else "失败"}")
                            appendLine("- 操作：${step.actionDescription}")
                            if (step.thinking.isNotBlank()) {
                                appendLine()
                                appendLine("**思考：**")
                                appendLine()
                                appendLine(step.thinking)
                            }
                            step.message?.takeIf { it.isNotBlank() }?.let {
                                appendLine()
                                appendLine("**结果信息：** $it")
                            }
                            step.screenshotPath?.let {
                                appendLine()
                                appendLine(
                                    "原始截图：`${screenshotRelativePath(step, annotated = false)}`",
                                )
                            }
                            step.annotatedScreenshotPath?.let {
                                appendLine(
                                    "标注截图：`${screenshotRelativePath(step, annotated = true)}`",
                                )
                            }
                            appendLine()
                        }

                        is HistoryTimelineItem.UserInstructionItem -> {
                            val event = item.event
                            appendLine("### 用户追加指令")
                            appendLine()
                            appendLine(
                                "- 类型：" +
                                    if (event.mode == RuntimeInstructionMode.CONTINUE_CURRENT) {
                                        "继续当前任务"
                                    } else {
                                        "添加为下一步"
                                    },
                            )
                            appendLine("- 添加时间：${displayTimeFormat.format(Date(event.timestamp))}")
                            appendLine("- 添加于：Step ${event.addedAtStep}")
                            item.appliedAtStep?.let {
                                appendLine("- 生效于：Step $it")
                            }
                            item.completedAtStep?.let {
                                appendLine("- 完成于：Step $it")
                            }
                            appendLine()
                            appendLine(event.content)
                            appendLine()
                        }

                        is HistoryTimelineItem.RoundCompletedItem -> {
                            val event = item.event
                            appendLine(
                                "**第 ${event.roundNumber} 轮结束：** " +
                                    if (event.success) "成功" else "失败",
                            )
                            event.message?.takeIf { it.isNotBlank() }?.let {
                                appendLine()
                                appendLine("结果：$it")
                            }
                            appendLine()
                        }

                        is HistoryTimelineItem.RepeatScheduledItem -> {
                            val event = item.event
                            appendLine("### 下一轮等待")
                            appendLine()
                            appendLine(
                                "- 随机等待：${event.delaySeconds} 秒（${formatDuration(event.delaySeconds)}）",
                            )
                            appendLine(
                                "- 计划时间：${displayTimeFormat.format(Date(event.nextRunAtMillis))}",
                            )
                            appendLine()
                        }
                    }
                }
        }

    private fun renderJson(task: TaskHistory): JSONObject =
        JSONObject().apply {
            put("schemaVersion", task.schemaVersion)
            put("id", task.id)
            put("taskDescription", task.taskDescription)
            put("startTime", task.startTime)
            put("endTime", task.endTime ?: JSONObject.NULL)
            put("success", task.success)
            put("completionMessage", task.completionMessage ?: JSONObject.NULL)
            put("roundCount", task.roundCount)
            put("stepCount", task.stepCount)
            put("instructionCount", task.instructionCount)

            put(
                "repeatConfig",
                task.repeatConfig?.let { config ->
                    JSONObject().apply {
                        put("enabled", config.enabled)
                        put("minMinutes", config.minMinutes)
                        put("maxMinutes", config.maxMinutes)
                    }
                } ?: JSONObject.NULL,
            )

            val stepsArray = JSONArray()
            task.steps.forEach { step ->
                stepsArray.put(
                    JSONObject().apply {
                        put("roundNumber", step.roundNumber)
                        put("stepNumber", step.stepNumber)
                        put("timestamp", step.timestamp)
                        put("thinking", step.thinking)
                        put("actionDescription", step.actionDescription)
                        put("success", step.success)
                        put("message", step.message ?: JSONObject.NULL)
                        put(
                            "screenshot",
                            if (step.screenshotPath != null) {
                                screenshotRelativePath(step, annotated = false)
                            } else {
                                JSONObject.NULL
                            },
                        )
                        put(
                            "annotatedScreenshot",
                            if (step.annotatedScreenshotPath != null) {
                                screenshotRelativePath(step, annotated = true)
                            } else {
                                JSONObject.NULL
                            },
                        )
                    },
                )
            }
            put("steps", stepsArray)

            val eventsArray = JSONArray()
            task.events.sortedBy { it.sequence }.forEach { event ->
                eventsArray.put(eventToJson(event))
            }
            put("events", eventsArray)
        }

    private fun eventToJson(event: HistoryEvent): JSONObject =
        JSONObject().apply {
            put("sequence", event.sequence)
            put("timestamp", event.timestamp)
            put("roundNumber", event.roundNumber)

            when (event) {
                is HistoryEvent.RoundStarted -> {
                    put("type", "round_started")
                }

                is HistoryEvent.AgentStepRecorded -> {
                    put("type", "agent_step_recorded")
                    put("stepNumber", event.stepNumber)
                }

                is HistoryEvent.UserInstructionAdded -> {
                    put("type", "user_instruction_added")
                    put("instructionId", event.instructionId)
                    put("content", event.content)
                    put("mode", event.mode.name)
                    put("addedAtStep", event.addedAtStep)
                }

                is HistoryEvent.UserInstructionApplied -> {
                    put("type", "user_instruction_applied")
                    put("instructionId", event.instructionId)
                    put("appliedAtStep", event.appliedAtStep)
                }

                is HistoryEvent.UserInstructionCompleted -> {
                    put("type", "user_instruction_completed")
                    put("instructionId", event.instructionId)
                    put("completedAtStep", event.completedAtStep)
                }

                is HistoryEvent.RoundCompleted -> {
                    put("type", "round_completed")
                    put("success", event.success)
                    put("message", event.message ?: JSONObject.NULL)
                }

                is HistoryEvent.RepeatScheduled -> {
                    put("type", "repeat_scheduled")
                    put("delaySeconds", event.delaySeconds)
                    put("nextRunAtMillis", event.nextRunAtMillis)
                }
            }
        }

    private fun screenshotRelativePath(
        step: HistoryStep,
        annotated: Boolean,
    ): String {
        val suffix = if (annotated) "_annotated" else ""
        return "screenshots/round_${
            step.roundNumber.toString().padStart(3, '0')
        }/step_${step.stepNumber.toString().padStart(3, '0')}$suffix.webp"
    }

    private fun writeTextEntry(
        zip: ZipOutputStream,
        name: String,
        content: String,
    ) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun addScreenshotToZip(
        zip: ZipOutputStream,
        sourcePath: String?,
        entryName: String,
    ) {
        if (sourcePath == null) return
        val source = File(sourcePath)
        if (!source.exists() || !source.isFile) return

        zip.putNextEntry(ZipEntry(entryName))
        source.inputStream().buffered().use { input ->
            input.copyTo(zip)
        }
        zip.closeEntry()
    }

    private fun formatDuration(seconds: Long): String =
        when {
            seconds < 60L -> "${seconds}秒"
            seconds < 3600L -> "${seconds / 60L}分${seconds % 60L}秒"
            else -> "${seconds / 3600L}时${(seconds % 3600L) / 60L}分${seconds % 60L}秒"
        }
}
