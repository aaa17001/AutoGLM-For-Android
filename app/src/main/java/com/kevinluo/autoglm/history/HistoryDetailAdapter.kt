package com.kevinluo.autoglm.history

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.kevinluo.autoglm.R
import com.kevinluo.autoglm.task.RuntimeInstructionMode
import com.kevinluo.autoglm.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter for the complete task-history timeline.
 */
class HistoryDetailAdapter(
    private val historyManager: HistoryManager,
    private val coroutineScope: CoroutineScope,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var task: TaskHistory? = null
    private var timelineItems: List<HistoryTimelineItem> = emptyList()
    private val loadedBitmaps = mutableMapOf<String, Bitmap>()
    private val loadingJobs = mutableMapOf<Int, Job>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun setTask(task: TaskHistory) {
        this.task = task
        timelineItems = buildHistoryTimeline(task)
        Logger.d(
            TAG,
            "Set task with ${task.stepCount} steps, ${task.instructionCount} runtime instructions",
        )
        notifyDataSetChanged()
    }

    fun cleanup() {
        loadingJobs.values.forEach { it.cancel() }
        loadingJobs.clear()
        loadedBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        loadedBitmaps.clear()
        Logger.d(TAG, "Cleaned up adapter resources")
    }

    override fun getItemViewType(position: Int): Int =
        when (timelineItems[position]) {
            is HistoryTimelineItem.Header -> TYPE_HEADER
            is HistoryTimelineItem.AgentStepItem -> TYPE_STEP
            else -> TYPE_EVENT
        }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER ->
                HeaderViewHolder(
                    inflater.inflate(R.layout.item_history_header, parent, false),
                )

            TYPE_STEP ->
                StepViewHolder(
                    inflater.inflate(R.layout.item_history_step, parent, false),
                )

            else ->
                EventViewHolder(
                    inflater.inflate(R.layout.item_history_event, parent, false),
                )
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        when (val item = timelineItems[position]) {
            is HistoryTimelineItem.Header ->
                (holder as HeaderViewHolder).bind(item.task)

            is HistoryTimelineItem.AgentStepItem ->
                (holder as StepViewHolder).bind(item.step)

            else ->
                (holder as EventViewHolder).bind(item)
        }
    }

    override fun getItemCount(): Int = timelineItems.size

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is StepViewHolder) {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) {
                loadingJobs[position]?.cancel()
                loadingJobs.remove(position)
            }
            holder.clearImage()
        }
    }

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val taskDescription: TextView = itemView.findViewById(R.id.taskDescription)
        private val statusText: TextView = itemView.findViewById(R.id.statusText)
        private val infoText: TextView = itemView.findViewById(R.id.infoText)

        fun bind(task: TaskHistory) {
            taskDescription.text = task.taskDescription

            val context = itemView.context
            if (task.success) {
                statusText.text = context.getString(R.string.history_success)
                statusText.setTextColor(ContextCompat.getColor(context, R.color.status_success))
            } else {
                statusText.text = context.getString(R.string.history_failed)
                statusText.setTextColor(ContextCompat.getColor(context, R.color.status_error))
            }

            infoText.text =
                context.getString(
                    R.string.history_info_extended_format,
                    dateFormat.format(Date(task.startTime)),
                    task.roundCount.coerceAtLeast(1),
                    task.stepCount,
                    task.instructionCount,
                    formatDuration(task.duration),
                )
        }
    }

    inner class EventViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.eventTitle)
        private val meta: TextView = itemView.findViewById(R.id.eventMeta)
        private val content: TextView = itemView.findViewById(R.id.eventContent)

        fun bind(item: HistoryTimelineItem) {
            val context = itemView.context
            meta.visibility = View.GONE
            content.visibility = View.GONE
            meta.text = ""
            content.text = ""

            when (item) {
                is HistoryTimelineItem.RoundHeader -> {
                    title.text =
                        context.getString(
                            R.string.history_round_format,
                            item.roundNumber,
                        )
                    meta.visibility = View.VISIBLE
                    meta.text = dateFormat.format(Date(item.timestamp))
                }

                is HistoryTimelineItem.UserInstructionItem -> {
                    title.text =
                        if (item.event.mode == RuntimeInstructionMode.CONTINUE_CURRENT) {
                            context.getString(R.string.history_instruction_continue)
                        } else {
                            context.getString(R.string.history_instruction_next_step)
                        }

                    val metadata = mutableListOf<String>()
                    metadata += dateFormat.format(Date(item.event.timestamp))
                    metadata +=
                        context.getString(
                            R.string.history_added_at_step,
                            item.event.addedAtStep,
                        )
                    item.appliedAtStep?.let {
                        metadata += context.getString(R.string.history_applied_at_step, it)
                    }
                    item.completedAtStep?.let {
                        metadata += context.getString(R.string.history_completed_at_step, it)
                    }

                    meta.visibility = View.VISIBLE
                    meta.text = metadata.joinToString(" · ")
                    content.visibility = View.VISIBLE
                    content.text = item.event.content
                }

                is HistoryTimelineItem.RoundCompletedItem -> {
                    title.text =
                        context.getString(
                            R.string.history_round_completed_format,
                            item.event.roundNumber,
                        )
                    meta.visibility = View.VISIBLE
                    meta.text = dateFormat.format(Date(item.event.timestamp))
                    content.visibility = View.VISIBLE
                    content.text =
                        buildString {
                            append(
                                if (item.event.success) {
                                    context.getString(R.string.history_success)
                                } else {
                                    context.getString(R.string.history_failed)
                                },
                            )
                            item.event.message?.takeIf { it.isNotBlank() }?.let {
                                append(" · ")
                                append(it)
                            }
                        }
                }

                is HistoryTimelineItem.RepeatScheduledItem -> {
                    title.text = context.getString(R.string.history_repeat_wait)
                    meta.visibility = View.VISIBLE
                    meta.text = dateFormat.format(Date(item.event.timestamp))
                    content.visibility = View.VISIBLE
                    content.text =
                        context.getString(
                            R.string.history_repeat_wait_detail_format,
                            item.event.delaySeconds,
                            formatSeconds(item.event.delaySeconds),
                        )
                }

                is HistoryTimelineItem.Header,
                is HistoryTimelineItem.AgentStepItem -> Unit
            }
        }
    }

    inner class StepViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val stepNumber: TextView = itemView.findViewById(R.id.stepNumber)
        private val actionDescription: TextView = itemView.findViewById(R.id.actionDescription)
        private val statusIcon: ImageView = itemView.findViewById(R.id.statusIcon)
        private val thinkingSection: LinearLayout = itemView.findViewById(R.id.thinkingSection)
        private val thinkingText: TextView = itemView.findViewById(R.id.thinkingText)
        private val screenshotSection: LinearLayout = itemView.findViewById(R.id.screenshotSection)
        private val screenshotImage: ImageView = itemView.findViewById(R.id.screenshotImage)
        private val btnOriginal: MaterialButton = itemView.findViewById(R.id.btnOriginal)
        private val btnAnnotated: MaterialButton = itemView.findViewById(R.id.btnAnnotated)
        private val messageText: TextView = itemView.findViewById(R.id.messageText)

        private var currentStep: HistoryStep? = null

        fun bind(step: HistoryStep) {
            currentStep = step
            stepNumber.text = step.stepNumber.toString()
            actionDescription.text = step.actionDescription

            val context = itemView.context
            if (step.success) {
                statusIcon.setImageResource(R.drawable.ic_check_circle)
                statusIcon.setColorFilter(ContextCompat.getColor(context, R.color.status_success))
            } else {
                statusIcon.setImageResource(R.drawable.ic_error)
                statusIcon.setColorFilter(ContextCompat.getColor(context, R.color.status_error))
            }

            if (step.thinking.isNotBlank()) {
                thinkingSection.visibility = View.VISIBLE
                thinkingText.text = step.thinking
            } else {
                thinkingSection.visibility = View.GONE
            }

            if (step.screenshotPath != null || step.annotatedScreenshotPath != null) {
                screenshotSection.visibility = View.VISIBLE
                screenshotImage.setImageDrawable(null)

                val defaultPath = step.annotatedScreenshotPath ?: step.screenshotPath
                loadScreenshot(defaultPath, screenshotImage)

                val hasAnnotated = step.annotatedScreenshotPath != null
                btnAnnotated.visibility = if (hasAnnotated) View.VISIBLE else View.GONE

                btnOriginal.setOnClickListener {
                    loadScreenshot(step.screenshotPath, screenshotImage)
                    btnOriginal.alpha = 1f
                    btnAnnotated.alpha = 0.5f
                }

                btnAnnotated.setOnClickListener {
                    loadScreenshot(step.annotatedScreenshotPath, screenshotImage)
                    btnOriginal.alpha = 0.5f
                    btnAnnotated.alpha = 1f
                }

                if (hasAnnotated) {
                    btnOriginal.alpha = 0.5f
                    btnAnnotated.alpha = 1f
                } else {
                    btnOriginal.alpha = 1f
                }
            } else {
                screenshotSection.visibility = View.GONE
            }

            if (!step.message.isNullOrBlank()) {
                messageText.visibility = View.VISIBLE
                messageText.text = step.message
            } else {
                messageText.visibility = View.GONE
            }
        }

        fun clearImage() {
            screenshotImage.setImageDrawable(null)
        }

        private fun loadScreenshot(
            path: String?,
            imageView: ImageView,
        ) {
            if (path == null) return

            loadedBitmaps[path]?.let {
                if (!it.isRecycled) {
                    imageView.setImageBitmap(it)
                    return
                }
            }

            val position = bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) {
                loadingJobs[position]?.cancel()
                loadingJobs[position] =
                    coroutineScope.launch {
                        val bitmap =
                            withContext(Dispatchers.IO) {
                                historyManager.getScreenshotBitmap(path)
                            }
                        bitmap?.let {
                            loadedBitmaps[path] = it
                            if (
                                currentStep?.screenshotPath == path ||
                                currentStep?.annotatedScreenshotPath == path
                            ) {
                                imageView.setImageBitmap(it)
                            }
                        }
                    }
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000L
        return formatSeconds(seconds)
    }

    private fun formatSeconds(seconds: Long): String =
        when {
            seconds < 60L -> "${seconds}秒"
            seconds < 3600L -> "${seconds / 60L}分${seconds % 60L}秒"
            else -> "${seconds / 3600L}时${(seconds % 3600L) / 60L}分"
        }

    companion object {
        private const val TAG = "HistoryDetailAdapter"
        private const val TYPE_HEADER = 0
        private const val TYPE_STEP = 1
        private const val TYPE_EVENT = 2
    }
}
