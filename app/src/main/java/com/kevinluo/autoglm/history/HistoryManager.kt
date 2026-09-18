package com.kevinluo.autoglm.history

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import com.kevinluo.autoglm.action.AgentAction
import com.kevinluo.autoglm.task.RepeatTaskConfig
import com.kevinluo.autoglm.task.RuntimeInstruction
import com.kevinluo.autoglm.task.RuntimeInstructionMode
import com.kevinluo.autoglm.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Manages task execution history storage and retrieval.
 *
 * This singleton class handles all operations related to task history including:
 * - Recording task execution steps with screenshots
 * - Persisting history to local storage
 * - Loading and querying historical tasks
 * - Managing history lifecycle (creation, deletion, cleanup)
 *
 * Usage example:
 * ```kotlin
 * val historyManager = HistoryManager.getInstance(context)
 * val task = historyManager.startTask("Open Settings app")
 * historyManager.recordStep(1, "Thinking...", action, "Tap on Settings", true)
 * historyManager.completeTask(true, "Task completed successfully")
 * ```
 *
 */
class HistoryManager private constructor(private val context: Context) {
    /** Directory for storing task history files. */
    private val historyDir: File by lazy {
        File(context.filesDir, HISTORY_DIR).also { it.mkdirs() }
    }

    /** Currently recording task, null if no task is being recorded. */
    private var currentTask: TaskHistory? = null

    /** Current repeat round. Legacy/non-repeat tasks use round 1. */
    private var currentRoundNumber: Int = 1

    /** Monotonic sequence used to preserve exact event ordering. */
    private var eventSequence: Long = 0L

    /** Protects event sequence assignment and timeline mutation. */
    private val eventLock = Any()

    /** Base64-encoded screenshot data for the current step. */
    private var currentScreenshotBase64: String? = null

    /** Width of the current screenshot in pixels. */
    private var currentScreenshotWidth: Int = 0

    /** Height of the current screenshot in pixels. */
    private var currentScreenshotHeight: Int = 0

    private val _historyList = MutableStateFlow<List<TaskHistory>>(emptyList())

    /** Observable list of all task histories, sorted by most recent first. */
    val historyList: StateFlow<List<TaskHistory>> = _historyList.asStateFlow()

    init {
        loadHistoryIndex()
    }

    /**
     * Starts recording a new task.
     *
     * Creates a new [TaskHistory] instance and sets it as the current task being recorded.
     * All subsequent calls to [recordStep] will add steps to this task until [completeTask] is called.
     *
     * @param taskDescription Human-readable description of the task being executed
     * @return The newly created [TaskHistory] instance
     *
     */
    fun startTask(
        taskDescription: String,
        repeatConfig: RepeatTaskConfig? = null,
    ): TaskHistory {
        val task =
            TaskHistory(
                taskDescription = taskDescription,
                repeatConfig = repeatConfig,
            )
        synchronized(eventLock) {
            currentTask = task
            currentRoundNumber = 1
            eventSequence = 0L
        }
        Logger.d(TAG, "Started recording task: ${task.id}")
        return task
    }

    /**
     * Marks the beginning of a task round.
     */
    fun startRound(roundNumber: Int) {
        require(roundNumber > 0) { "roundNumber must be greater than zero" }
        currentRoundNumber = roundNumber
        appendEvent { sequence ->
            HistoryEvent.RoundStarted(
                sequence = sequence,
                roundNumber = roundNumber,
            )
        }
    }

    /**
     * Records the completion of a task round without completing the whole task session.
     */
    fun completeRound(
        roundNumber: Int,
        success: Boolean,
        message: String?,
    ) {
        appendEvent { sequence ->
            HistoryEvent.RoundCompleted(
                sequence = sequence,
                roundNumber = roundNumber,
                success = success,
                message = message,
            )
        }
    }

    /**
     * Records a runtime instruction at the moment the user adds it.
     */
    fun recordInstructionAdded(instruction: RuntimeInstruction) {
        appendEvent { sequence ->
            HistoryEvent.UserInstructionAdded(
                sequence = sequence,
                timestamp = instruction.createdAt,
                roundNumber = instruction.roundNumber,
                instructionId = instruction.id,
                content = instruction.content,
                mode = instruction.mode,
                addedAtStep = instruction.addedAtStep,
            )
        }
    }

    /**
     * Records the Agent step where a runtime instruction became active.
     */
    fun recordInstructionApplied(instruction: RuntimeInstruction) {
        val appliedAtStep = instruction.appliedAtStep ?: return
        appendEvent { sequence ->
            HistoryEvent.UserInstructionApplied(
                sequence = sequence,
                roundNumber = instruction.roundNumber,
                instructionId = instruction.id,
                appliedAtStep = appliedAtStep,
            )
        }
    }

    /**
     * Records the Agent step where a queued follow-up instruction completed.
     */
    fun recordInstructionCompleted(instruction: RuntimeInstruction) {
        val completedAtStep = instruction.completedAtStep ?: return
        appendEvent { sequence ->
            HistoryEvent.UserInstructionCompleted(
                sequence = sequence,
                roundNumber = instruction.roundNumber,
                instructionId = instruction.id,
                completedAtStep = completedAtStep,
            )
        }
    }

    /**
     * Records the randomized delay selected before the next repeat round.
     */
    fun recordRepeatScheduled(
        roundNumber: Int,
        delaySeconds: Long,
        nextRunAtMillis: Long,
    ) {
        require(delaySeconds >= 0L) { "delaySeconds must not be negative" }
        appendEvent { sequence ->
            HistoryEvent.RepeatScheduled(
                sequence = sequence,
                roundNumber = roundNumber,
                delaySeconds = delaySeconds,
                nextRunAtMillis = nextRunAtMillis,
            )
        }
    }

    /**
     * Sets the current screenshot for the next step.
     *
     * The screenshot data will be used when [recordStep] is called to save
     * both the original and annotated versions of the screenshot.
     *
     * @param base64Data Base64-encoded screenshot image data (WebP format)
     * @param width Screenshot width in pixels
     * @param height Screenshot height in pixels
     *
     */
    fun setCurrentScreenshot(base64Data: String, width: Int, height: Int) {
        currentScreenshotBase64 = base64Data
        currentScreenshotWidth = width
        currentScreenshotHeight = height
    }

    /**
     * Records a step in the current task.
     *
     * Saves the step information including thinking, action, and screenshot to the current task.
     * If a screenshot is available (set via [setCurrentScreenshot]), it will be saved to disk
     * and optionally annotated with action visualization.
     *
     * @param stepNumber Sequential step number within the task
     * @param thinking Model's reasoning/thinking for this step
     * @param action The agent action executed, or null if no action
     * @param actionDescription Human-readable description of the action
     * @param success Whether the step executed successfully
     * @param message Optional additional message or error details
     *
     */
    suspend fun recordStep(
        stepNumber: Int,
        thinking: String,
        action: AgentAction?,
        actionDescription: String,
        success: Boolean,
        message: String? = null,
    ) = withContext(Dispatchers.IO) {
        val task = currentTask ?: return@withContext

        var screenshotPath: String? = null
        var annotatedPath: String? = null

        // Save screenshot if available
        currentScreenshotBase64?.let { base64 ->
            try {
                // Decode base64 to raw bytes (already WebP format)
                val webpBytes = Base64.decode(base64, Base64.DEFAULT)

                // Save original screenshot directly without re-compression
                screenshotPath = saveScreenshotBytes(task.id, currentRoundNumber, stepNumber, webpBytes, false)

                // Create and save annotated screenshot if action has visual annotation
                if (action != null) {
                    val annotation =
                        ScreenshotAnnotator.createAnnotation(
                            action,
                            currentScreenshotWidth,
                            currentScreenshotHeight,
                        )
                    if (annotation !is ActionAnnotation.None) {
                        // Only decode bitmap when we need to annotate
                        val bitmap = BitmapFactory.decodeByteArray(webpBytes, 0, webpBytes.size)
                        if (bitmap != null) {
                            // Calculate scaled density based on screenshot size vs typical screen size
                            // This ensures annotations look proportional on scaled screenshots
                            val baseDensity = context.resources.displayMetrics.density
                            val scaleFactor = bitmap.width.toFloat() / context.resources.displayMetrics.widthPixels
                            val scaledDensity = baseDensity * scaleFactor
                            val annotatedBitmap = ScreenshotAnnotator.annotate(bitmap, annotation, scaledDensity)
                            annotatedPath =
                                saveScreenshotBitmap(
                                    task.id,
                                    currentRoundNumber,
                                    stepNumber,
                                    annotatedBitmap,
                                    true,
                                )
                            annotatedBitmap.recycle()
                            bitmap.recycle()
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to save screenshot for step $stepNumber", e)
            }
        }

        val step =
            HistoryStep(
                stepNumber = stepNumber,
                roundNumber = currentRoundNumber,
                thinking = thinking,
                action = action,
                actionDescription = actionDescription,
                screenshotPath = screenshotPath,
                annotatedScreenshotPath = annotatedPath,
                success = success,
                message = message,
            )

        task.steps.add(step)
        appendEvent { sequence ->
            HistoryEvent.AgentStepRecorded(
                sequence = sequence,
                timestamp = step.timestamp,
                roundNumber = step.roundNumber,
                stepNumber = step.stepNumber,
            )
        }
        Logger.d(TAG, "Recorded step $stepNumber for task ${task.id}")

        // Clear current screenshot
        currentScreenshotBase64 = null
    }

    /**
     * Completes the current task recording.
     *
     * Finalizes the task with success/failure status and saves it to persistent storage.
     * Empty tasks (no steps recorded) are discarded. The history list is updated and
     * old entries are trimmed if the maximum count is exceeded.
     *
     * @param success Whether the task completed successfully
     * @param message Optional completion message or error description
     *
     */
    suspend fun completeTask(success: Boolean, message: String?) = withContext(Dispatchers.IO) {
        val task = currentTask ?: return@withContext

        // Don't save empty tasks (no steps recorded)
        if (task.steps.isEmpty()) {
            Logger.d(TAG, "Skipping empty task ${task.id}")
            synchronized(eventLock) {
                currentTask = null
                currentRoundNumber = 1
                eventSequence = 0L
            }
            currentScreenshotBase64 = null
            return@withContext
        }

        task.endTime = System.currentTimeMillis()
        task.success = success
        task.completionMessage = message

        // Save task to disk
        saveTask(task)

        // Update history list
        val updatedList = _historyList.value.toMutableList()
        updatedList.add(0, task)

        // Trim old history if needed
        while (updatedList.size > MAX_HISTORY_COUNT) {
            val removed = updatedList.removeAt(updatedList.size - 1)
            deleteTaskFiles(removed.id)
        }

        _historyList.value = updatedList
        saveHistoryIndex()

        Logger.d(TAG, "Completed task ${task.id}, success=$success")
        synchronized(eventLock) {
            currentTask = null
            currentRoundNumber = 1
            eventSequence = 0L
        }
    }

    /**
     * Gets a task history by ID.
     *
     * Loads the complete task history including all steps from persistent storage.
     *
     * @param taskId Unique identifier of the task to retrieve
     * @return The [TaskHistory] if found, null otherwise
     *
     */
    suspend fun getTask(taskId: String): TaskHistory? = withContext(Dispatchers.IO) {
        loadTask(taskId)
    }

    /**
     * Deletes a task history.
     *
     * Removes the task and all associated files (screenshots) from storage.
     *
     * @param taskId Unique identifier of the task to delete
     *
     */
    suspend fun deleteTask(taskId: String) = withContext(Dispatchers.IO) {
        deleteTaskFiles(taskId)
        _historyList.value = _historyList.value.filter { it.id != taskId }
        saveHistoryIndex()
    }

    /**
     * Deletes multiple task histories.
     *
     * Batch deletion of tasks and their associated files.
     *
     * @param taskIds Set of unique identifiers of tasks to delete
     *
     */
    suspend fun deleteTasks(taskIds: Set<String>) = withContext(Dispatchers.IO) {
        taskIds.forEach { taskId ->
            deleteTaskFiles(taskId)
        }
        _historyList.value = _historyList.value.filter { it.id !in taskIds }
        saveHistoryIndex()
    }

    /**
     * Clears all history.
     *
     * Removes all task histories and their associated files from storage.
     *
     */
    suspend fun clearAllHistory() = withContext(Dispatchers.IO) {
        historyDir.listFiles()?.forEach { it.deleteRecursively() }
        _historyList.value = emptyList()
        saveHistoryIndex()
    }

    /**
     * Gets the screenshot bitmap for a step.
     *
     * Loads and decodes a screenshot image from the given file path.
     *
     * @param path Absolute file path to the screenshot, or null
     * @return Decoded [Bitmap] if the file exists and is valid, null otherwise
     *
     */
    fun getScreenshotBitmap(path: String?): Bitmap? {
        if (path == null) return null
        val file = File(path)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(path)
    }

    // Private helper methods

    /**
     * Saves raw WebP bytes directly to file (no re-compression).
     *
     * @param taskId Task identifier for directory organization
     * @param stepNumber Step number for filename
     * @param webpBytes Raw WebP image bytes
     * @param annotated Whether this is an annotated screenshot
     * @return Absolute file path of the saved screenshot
     */
    private fun saveScreenshotBytes(
        taskId: String,
        roundNumber: Int,
        stepNumber: Int,
        webpBytes: ByteArray,
        annotated: Boolean,
    ): String {
        val roundDir = getRoundDirectory(taskId, roundNumber)
        val suffix = if (annotated) "_annotated" else ""
        val file = File(roundDir, "step_${stepNumber.toString().padStart(3, '0')}$suffix.webp")

        FileOutputStream(file).use { out ->
            out.write(webpBytes)
        }

        return file.absolutePath
    }

    /**
     * Saves bitmap as WebP (used for annotated screenshots).
     *
     * @param taskId Task identifier for directory organization
     * @param stepNumber Step number for filename
     * @param bitmap Bitmap to save
     * @param annotated Whether this is an annotated screenshot
     * @return Absolute file path of the saved screenshot
     */
    private fun saveScreenshotBitmap(
        taskId: String,
        roundNumber: Int,
        stepNumber: Int,
        bitmap: Bitmap,
        annotated: Boolean,
    ): String {
        val roundDir = getRoundDirectory(taskId, roundNumber)
        val suffix = if (annotated) "_annotated" else ""
        val file = File(roundDir, "step_${stepNumber.toString().padStart(3, '0')}$suffix.webp")

        FileOutputStream(file).use { out ->
            @Suppress("DEPRECATION")
            val format =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    Bitmap.CompressFormat.WEBP
                }
            bitmap.compress(format, 85, out)
        }

        return file.absolutePath
    }

    /**
     * Returns the directory used for screenshots belonging to one task round.
     */
    private fun getRoundDirectory(taskId: String, roundNumber: Int): File {
        val taskDir = File(historyDir, taskId).also { it.mkdirs() }
        return File(
            taskDir,
            "round_${roundNumber.toString().padStart(3, '0')}",
        ).also { it.mkdirs() }
    }

    /**
     * Appends an event with a strictly increasing sequence number.
     */
    private fun appendEvent(factory: (Long) -> HistoryEvent) {
        synchronized(eventLock) {
            val task = currentTask ?: return@synchronized
            eventSequence += 1L
            task.events.add(factory(eventSequence))
        }
    }

    /**
     * Saves a task's metadata to JSON file.
     *
     * @param task Task history to save
     */
    private fun saveTask(task: TaskHistory) {
        val taskDir = File(historyDir, task.id).also { it.mkdirs() }
        val metaFile = File(taskDir, "meta.json")

        val json =
            JSONObject().apply {
                put("schemaVersion", task.schemaVersion)
                put("id", task.id)
                put("taskDescription", task.taskDescription)
                put("startTime", task.startTime)
                put("endTime", task.endTime ?: JSONObject.NULL)
                put("success", task.success)
                put("completionMessage", task.completionMessage ?: JSONObject.NULL)
                put(
                    "repeatConfig",
                    task.repeatConfig?.let { repeatConfig ->
                        JSONObject().apply {
                            put("enabled", repeatConfig.enabled)
                            put("minMinutes", repeatConfig.minMinutes)
                            put("maxMinutes", repeatConfig.maxMinutes)
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
                            put("screenshotPath", step.screenshotPath ?: JSONObject.NULL)
                            put("annotatedScreenshotPath", step.annotatedScreenshotPath ?: JSONObject.NULL)
                            put("success", step.success)
                            put("message", step.message ?: JSONObject.NULL)
                        },
                    )
                }
                put("steps", stepsArray)

                val eventsArray = JSONArray()
                task.events
                    .sortedBy { it.sequence }
                    .forEach { event ->
                        eventsArray.put(historyEventToJson(event))
                    }
                put("events", eventsArray)
            }

        metaFile.writeText(json.toString(2))
    }

    private fun historyEventToJson(event: HistoryEvent): JSONObject =
        JSONObject().apply {
            put("sequence", event.sequence)
            put("timestamp", event.timestamp)
            put("roundNumber", event.roundNumber)

            when (event) {
                is HistoryEvent.RoundStarted -> {
                    put("type", EVENT_ROUND_STARTED)
                }

                is HistoryEvent.AgentStepRecorded -> {
                    put("type", EVENT_AGENT_STEP_RECORDED)
                    put("stepNumber", event.stepNumber)
                }

                is HistoryEvent.UserInstructionAdded -> {
                    put("type", EVENT_USER_INSTRUCTION_ADDED)
                    put("instructionId", event.instructionId)
                    put("content", event.content)
                    put("mode", event.mode.name)
                    put("addedAtStep", event.addedAtStep)
                }

                is HistoryEvent.UserInstructionApplied -> {
                    put("type", EVENT_USER_INSTRUCTION_APPLIED)
                    put("instructionId", event.instructionId)
                    put("appliedAtStep", event.appliedAtStep)
                }

                is HistoryEvent.UserInstructionCompleted -> {
                    put("type", EVENT_USER_INSTRUCTION_COMPLETED)
                    put("instructionId", event.instructionId)
                    put("completedAtStep", event.completedAtStep)
                }

                is HistoryEvent.RoundCompleted -> {
                    put("type", EVENT_ROUND_COMPLETED)
                    put("success", event.success)
                    put("message", event.message ?: JSONObject.NULL)
                }

                is HistoryEvent.RepeatScheduled -> {
                    put("type", EVENT_REPEAT_SCHEDULED)
                    put("delaySeconds", event.delaySeconds)
                    put("nextRunAtMillis", event.nextRunAtMillis)
                }
            }
        }

    /**
     * Loads a task from its JSON metadata file.
     *
     * @param taskId Task identifier to load
     * @return Loaded TaskHistory, or null if not found or invalid
     */
    private fun loadTask(taskId: String): TaskHistory? {
        val metaFile = File(historyDir, "$taskId/meta.json")
        if (!metaFile.exists()) return null

        return try {
            val json = JSONObject(metaFile.readText())
            val schemaVersion = json.optInt("schemaVersion", 1)
            val steps = mutableListOf<HistoryStep>()
            val events = mutableListOf<HistoryEvent>()

            val stepsArray = json.optJSONArray("steps")
            if (stepsArray != null) {
                for (i in 0 until stepsArray.length()) {
                    val stepJson = stepsArray.getJSONObject(i)
                    steps.add(
                        HistoryStep(
                            stepNumber = stepJson.getInt("stepNumber"),
                            roundNumber = stepJson.optInt("roundNumber", 1),
                            timestamp = stepJson.getLong("timestamp"),
                            thinking = stepJson.getString("thinking"),
                            // Action is not serialized
                            action = null,
                            actionDescription = stepJson.getString("actionDescription"),
                            screenshotPath = stepJson.optNullableString("screenshotPath"),
                            annotatedScreenshotPath = stepJson.optNullableString("annotatedScreenshotPath"),
                            success = stepJson.getBoolean("success"),
                            message = stepJson.optNullableString("message"),
                        ),
                    )
                }
            }

            val eventsArray = json.optJSONArray("events")
            if (eventsArray != null) {
                for (i in 0 until eventsArray.length()) {
                    historyEventFromJson(eventsArray.getJSONObject(i))?.let(events::add)
                }
            }

            val repeatConfig =
                json.optJSONObject("repeatConfig")?.let { repeatJson ->
                    RepeatTaskConfig(
                        enabled = repeatJson.optBoolean("enabled", false),
                        minMinutes =
                        repeatJson.optInt(
                            "minMinutes",
                            RepeatTaskConfig.DEFAULT_MIN_MINUTES,
                        ),
                        maxMinutes =
                        repeatJson.optInt(
                            "maxMinutes",
                            RepeatTaskConfig.DEFAULT_MAX_MINUTES,
                        ),
                    ).takeIf { it.isValid() }
                }

            TaskHistory(
                id = json.getString("id"),
                schemaVersion = schemaVersion,
                taskDescription = json.getString("taskDescription"),
                startTime = json.getLong("startTime"),
                endTime =
                if (json.has("endTime") && !json.isNull("endTime")) {
                    json.getLong("endTime")
                } else {
                    null
                },
                success = json.optBoolean("success", false),
                completionMessage = json.optNullableString("completionMessage"),
                repeatConfig = repeatConfig,
                steps = steps,
                events = events,
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load task $taskId", e)
            null
        }
    }

    private fun historyEventFromJson(json: JSONObject): HistoryEvent? {
        val sequence = json.optLong("sequence", 0L)
        val timestamp = json.optLong("timestamp", 0L)
        val roundNumber = json.optInt("roundNumber", 1)

        return when (json.optString("type")) {
            EVENT_ROUND_STARTED ->
                HistoryEvent.RoundStarted(
                    sequence = sequence,
                    timestamp = timestamp,
                    roundNumber = roundNumber,
                )

            EVENT_AGENT_STEP_RECORDED ->
                HistoryEvent.AgentStepRecorded(
                    sequence = sequence,
                    timestamp = timestamp,
                    roundNumber = roundNumber,
                    stepNumber = json.getInt("stepNumber"),
                )

            EVENT_USER_INSTRUCTION_ADDED -> {
                val mode =
                    runCatching {
                        RuntimeInstructionMode.valueOf(json.getString("mode"))
                    }.getOrNull() ?: return null

                HistoryEvent.UserInstructionAdded(
                    sequence = sequence,
                    timestamp = timestamp,
                    roundNumber = roundNumber,
                    instructionId = json.getString("instructionId"),
                    content = json.getString("content"),
                    mode = mode,
                    addedAtStep = json.getInt("addedAtStep"),
                )
            }

            EVENT_USER_INSTRUCTION_APPLIED ->
                HistoryEvent.UserInstructionApplied(
                    sequence = sequence,
                    timestamp = timestamp,
                    roundNumber = roundNumber,
                    instructionId = json.getString("instructionId"),
                    appliedAtStep = json.getInt("appliedAtStep"),
                )

            EVENT_USER_INSTRUCTION_COMPLETED ->
                HistoryEvent.UserInstructionCompleted(
                    sequence = sequence,
                    timestamp = timestamp,
                    roundNumber = roundNumber,
                    instructionId = json.getString("instructionId"),
                    completedAtStep = json.getInt("completedAtStep"),
                )

            EVENT_ROUND_COMPLETED ->
                HistoryEvent.RoundCompleted(
                    sequence = sequence,
                    timestamp = timestamp,
                    roundNumber = roundNumber,
                    success = json.optBoolean("success", false),
                    message = json.optNullableString("message"),
                )

            EVENT_REPEAT_SCHEDULED ->
                HistoryEvent.RepeatScheduled(
                    sequence = sequence,
                    timestamp = timestamp,
                    roundNumber = roundNumber,
                    delaySeconds = json.getLong("delaySeconds"),
                    nextRunAtMillis = json.getLong("nextRunAtMillis"),
                )

            else -> null
        }
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) {
            null
        } else {
            optString(key).takeIf { it.isNotEmpty() }
        }

    /**
     * Deletes all files associated with a task.
     *
     * @param taskId Task identifier whose files should be deleted
     */
    private fun deleteTaskFiles(taskId: String) {
        File(historyDir, taskId).deleteRecursively()
    }

    /**
     * Loads the history index from persistent storage.
     *
     * Populates [_historyList] with all saved task histories.
     */
    private fun loadHistoryIndex() {
        val indexFile = File(historyDir, INDEX_FILE)
        if (!indexFile.exists()) return

        try {
            val json = JSONArray(indexFile.readText())
            val list = mutableListOf<TaskHistory>()

            for (i in 0 until json.length()) {
                val taskId = json.getString(i)
                loadTask(taskId)?.let { list.add(it) }
            }

            _historyList.value = list
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load history index", e)
        }
    }

    /**
     * Saves the history index to persistent storage.
     *
     * Writes the list of task IDs to the index file for quick loading on startup.
     */
    private fun saveHistoryIndex() {
        val indexFile = File(historyDir, INDEX_FILE)
        val json = JSONArray()
        _historyList.value.forEach { json.put(it.id) }
        indexFile.writeText(json.toString())
    }

    companion object {
        private const val TAG = "HistoryManager"
        private const val HISTORY_DIR = "task_history"
        private const val INDEX_FILE = "history_index.json"
        private const val MAX_HISTORY_COUNT = 50

        private const val EVENT_ROUND_STARTED = "round_started"
        private const val EVENT_AGENT_STEP_RECORDED = "agent_step_recorded"
        private const val EVENT_USER_INSTRUCTION_ADDED = "user_instruction_added"
        private const val EVENT_USER_INSTRUCTION_APPLIED = "user_instruction_applied"
        private const val EVENT_USER_INSTRUCTION_COMPLETED = "user_instruction_completed"
        private const val EVENT_ROUND_COMPLETED = "round_completed"
        private const val EVENT_REPEAT_SCHEDULED = "repeat_scheduled"

        @Volatile
        private var instance: HistoryManager? = null

        /**
         * Gets the singleton instance of HistoryManager.
         *
         * @param context Android context, application context will be used
         * @return The singleton HistoryManager instance
         */
        fun getInstance(context: Context): HistoryManager = instance ?: synchronized(this) {
            instance ?: HistoryManager(context.applicationContext).also { instance = it }
        }
    }
}
