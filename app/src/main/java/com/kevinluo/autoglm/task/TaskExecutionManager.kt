package com.kevinluo.autoglm.task

import android.content.Context
import android.os.SystemClock
import com.kevinluo.autoglm.ComponentManager
import com.kevinluo.autoglm.action.AgentAction
import com.kevinluo.autoglm.agent.AgentState
import com.kevinluo.autoglm.agent.PhoneAgent
import com.kevinluo.autoglm.agent.PhoneAgentListener
import com.kevinluo.autoglm.history.HistoryManager
import com.kevinluo.autoglm.ui.FloatingWindowStateManager
import com.kevinluo.autoglm.ui.TaskStatus
import com.kevinluo.autoglm.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Singleton manager for task execution state.
 *
 * This is the single source of truth for a complete task session. A session can contain multiple
 * repeat rounds, and each round can accept runtime user instructions.
 */
object TaskExecutionManager :
    PhoneAgentListener,
    RuntimeInstructionSource {
    private const val TAG = "TaskExecutionManager"
    private const val PHONE_AGENT_POLL_INTERVAL_MS = 500L
    private const val CANCELLED_MESSAGE = "任务已取消"

    private var applicationContext: Context? = null
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _taskState = MutableStateFlow(TaskExecutionState())

    /** Observable task/session state. */
    val taskState: StateFlow<TaskExecutionState> = _taskState.asStateFlow()

    private val _steps = MutableStateFlow<List<TaskStep>>(emptyList())

    /** Observable Agent steps for the current round. */
    val steps: StateFlow<List<TaskStep>> = _steps.asStateFlow()

    private val instructionQueue = RuntimeInstructionQueue()
    private val _runtimeInstructions = MutableStateFlow<List<RuntimeInstruction>>(emptyList())

    /** Runtime instructions for the current round, including completed items until the round ends. */
    val runtimeInstructions: StateFlow<List<RuntimeInstruction>> = _runtimeInstructions.asStateFlow()

    private var sessionJob: Job? = null
    private var currentRoundNumber: Int = 0
    private var currentRepeatConfig: RepeatTaskConfig = RepeatTaskConfig()

    /**
     * Initializes the manager with application context.
     */
    fun initialize(context: Context) {
        applicationContext = context.applicationContext
        Logger.i(TAG, "TaskExecutionManager initialized")
        observePhoneAgentAvailability()
    }

    private fun observePhoneAgentAvailability() {
        managerScope.launch {
            var lastPhoneAgent: PhoneAgent? = null

            while (true) {
                val componentManager = getComponentManager()
                val currentPhoneAgent = componentManager?.phoneAgent

                if (currentPhoneAgent != lastPhoneAgent) {
                    lastPhoneAgent?.let {
                        it.setListener(null)
                        Logger.i(TAG, "Unregistered as PhoneAgentListener")
                    }

                    currentPhoneAgent?.let {
                        it.setListener(this@TaskExecutionManager)
                        Logger.i(TAG, "Registered as PhoneAgentListener")
                    }

                    lastPhoneAgent = currentPhoneAgent
                }

                delay(PHONE_AGENT_POLL_INTERVAL_MS)
            }
        }
    }

    private fun getComponentManager(): ComponentManager? {
        val ctx = applicationContext ?: return null
        return ComponentManager.getInstance(ctx)
    }

    private fun getHistoryManager(): HistoryManager? {
        val ctx = applicationContext ?: return null
        return HistoryManager.getInstance(ctx)
    }

    // region Task/session control

    /**
     * Starts a new session. Round 1 starts immediately.
     */
    fun startTask(
        description: String,
        repeatConfig: RepeatTaskConfig = RepeatTaskConfig(),
    ): Boolean {
        if (description.isBlank()) {
            Logger.w(TAG, "Cannot start task: empty description")
            return false
        }

        if (!repeatConfig.isValid()) {
            Logger.w(TAG, "Cannot start task: invalid repeat config $repeatConfig")
            return false
        }

        if (!canStartTask()) {
            Logger.w(TAG, "Cannot start task: preconditions not met")
            return false
        }

        val componentManager = getComponentManager() ?: return false
        componentManager.phoneAgent ?: return false

        currentRepeatConfig = repeatConfig
        currentRoundNumber = 0
        instructionQueue.clear()
        _runtimeInstructions.value = emptyList()
        _steps.value = emptyList()

        _taskState.value =
            TaskExecutionState(
                status = TaskStatus.RUNNING,
                taskDescription = description,
                roundNumber = 1,
                repeatEnabled = repeatConfig.enabled,
            )

        Logger.i(
            TAG,
            "Starting task session: repeat=${repeatConfig.enabled}, task=${description.take(50)}...",
        )

        val job =
            managerScope.launch(start = CoroutineStart.LAZY) {
                runSession(
                    description = description,
                    repeatConfig = repeatConfig,
                )
            }
        sessionJob = job
        job.start()
        return true
    }

    private suspend fun runSession(
        description: String,
        repeatConfig: RepeatTaskConfig,
    ) {
        val historyManager = getHistoryManager()
        val ctx = applicationContext

        var sessionSuccess = false
        var finalMessage = ""
        var finalStatus = TaskStatus.FAILED

        try {
            historyManager?.startTask(
                taskDescription = description,
                repeatConfig = repeatConfig,
            )
            if (ctx != null) {
                FloatingWindowStateManager.onTaskStarted(ctx)
            }

            var roundNumber = 1
            while (currentCoroutineContext().isActive) {
                currentRoundNumber = roundNumber
                clearRuntimeInstructionsForNewRound()
                _steps.value = emptyList()

                historyManager?.startRound(roundNumber)

                _taskState.value =
                    TaskExecutionState(
                        status = TaskStatus.RUNNING,
                        taskDescription = description,
                        roundNumber = roundNumber,
                        repeatEnabled = repeatConfig.enabled,
                    )

                val agent = getComponentManager()?.phoneAgent
                if (agent == null) {
                    finalMessage = "代理未就绪，无法开始下一轮"
                    finalStatus = TaskStatus.FAILED
                    Logger.w(TAG, finalMessage)
                    break
                }
                agent.setListener(this)

                Logger.i(TAG, "Starting round $roundNumber")
                val result =
                    agent.runManagedRound(
                        task = description,
                        instructionSource = this,
                    )

                historyManager?.completeRound(
                    roundNumber = roundNumber,
                    success = result.success,
                    message = result.message,
                )

                finalMessage = result.message

                if (!result.success) {
                    finalStatus = TaskStatus.FAILED
                    Logger.w(TAG, "Round $roundNumber failed: ${result.message}")
                    break
                }

                if (!repeatConfig.enabled) {
                    sessionSuccess = true
                    finalStatus = TaskStatus.COMPLETED
                    Logger.i(TAG, "Single-round task session completed")
                    break
                }

                val delaySeconds = repeatConfig.randomDelaySeconds()
                val nextRunAtMillis = System.currentTimeMillis() + delaySeconds * 1000L

                historyManager?.recordRepeatScheduled(
                    roundNumber = roundNumber,
                    delaySeconds = delaySeconds,
                    nextRunAtMillis = nextRunAtMillis,
                )

                waitForNextRound(
                    delaySeconds = delaySeconds,
                    nextRunAtMillis = nextRunAtMillis,
                )

                roundNumber++
            }
        } catch (e: CancellationException) {
            finalMessage = CANCELLED_MESSAGE
            finalStatus = TaskStatus.FAILED
            Logger.i(TAG, "Task session cancelled")
        } catch (e: Exception) {
            finalMessage = e.message ?: "Unknown error"
            finalStatus = TaskStatus.FAILED
            Logger.e(TAG, "Task session error: ${e.message}", e)
        } finally {
            val completionMessage =
                finalMessage.ifBlank {
                    if (sessionSuccess) "任务已完成" else CANCELLED_MESSAGE
                }

            _taskState.value =
                _taskState.value.copy(
                    status = finalStatus,
                    resultMessage = completionMessage,
                    repeatRemainingSeconds = 0L,
                    nextRunAtMillis = null,
                    pendingInstructionCount = 0,
                    queuedNextStepCount = 0,
                )

            withContext(NonCancellable) {
                historyManager?.completeTask(
                    success = sessionSuccess,
                    message = completionMessage,
                )
            }

            instructionQueue.clear()
            _runtimeInstructions.value = emptyList()
            FloatingWindowStateManager.onTaskCompleted()

            currentRepeatConfig = RepeatTaskConfig()
            sessionJob = null
        }
    }

    private suspend fun waitForNextRound(
        delaySeconds: Long,
        nextRunAtMillis: Long,
    ) {
        val targetElapsedRealtime = SystemClock.elapsedRealtime() + delaySeconds * 1000L

        while (true) {
            currentCoroutineContext().ensureActive()

            val remainingMillis = targetElapsedRealtime - SystemClock.elapsedRealtime()
            if (remainingMillis <= 0L) {
                break
            }

            val remainingSeconds = (remainingMillis + 999L) / 1000L
            _taskState.value =
                _taskState.value.copy(
                    status = TaskStatus.WAITING_REPEAT,
                    repeatRemainingSeconds = remainingSeconds,
                    nextRunAtMillis = nextRunAtMillis,
                )

            delay(minOf(remainingMillis, 1000L))
        }

        _taskState.value =
            _taskState.value.copy(
                repeatRemainingSeconds = 0L,
                nextRunAtMillis = null,
            )
    }

    /**
     * Adds a user instruction while the current round is running or paused.
     */
    fun addRuntimeInstruction(
        content: String,
        mode: RuntimeInstructionMode,
    ): Boolean {
        val normalized = content.trim()
        if (normalized.isEmpty()) return false

        val status = _taskState.value.status
        if (status != TaskStatus.RUNNING && status != TaskStatus.PAUSED) {
            Logger.w(TAG, "Runtime instruction rejected in state $status")
            return false
        }

        val instruction =
            RuntimeInstruction(
                content = normalized,
                mode = mode,
                roundNumber = currentRoundNumber.coerceAtLeast(1),
                addedAtStep = _taskState.value.stepNumber,
            )

        instructionQueue.add(instruction)
        _runtimeInstructions.value = _runtimeInstructions.value + instruction
        getHistoryManager()?.recordInstructionAdded(instruction)
        updateInstructionCounts()

        Logger.i(
            TAG,
            "Queued runtime instruction: mode=$mode, step=${instruction.addedAtStep}",
        )
        return true
    }

    /**
     * Pauses the currently running Agent round.
     */
    fun pauseTask(): Boolean {
        val agent = getComponentManager()?.phoneAgent ?: return false

        val paused = agent.pause()
        if (paused) {
            Logger.i(TAG, "Task paused")
            _taskState.value = _taskState.value.copy(status = TaskStatus.PAUSED)
        } else {
            Logger.w(TAG, "Failed to pause task")
        }
        return paused
    }

    /**
     * Resumes a paused Agent round.
     */
    fun resumeTask(): Boolean {
        val agent = getComponentManager()?.phoneAgent ?: return false

        val resumed = agent.resume()
        if (resumed) {
            Logger.i(TAG, "Task resumed")
            _taskState.value = _taskState.value.copy(status = TaskStatus.RUNNING)
        } else {
            Logger.w(TAG, "Failed to resume task")
        }
        return resumed
    }

    /**
     * Cancels the whole session, including a running Agent round or repeat wait.
     */
    fun cancelTask() {
        Logger.i(TAG, "Cancelling task session")

        getComponentManager()?.phoneAgent?.let { agent ->
            if (agent.isRunning() || agent.isPaused()) {
                agent.cancel()
            }
        }

        instructionQueue.clear()
        updateInstructionCounts()

        _taskState.value =
            _taskState.value.copy(
                status = TaskStatus.FAILED,
                resultMessage = CANCELLED_MESSAGE,
                repeatRemainingSeconds = 0L,
                nextRunAtMillis = null,
            )

        sessionJob?.cancel()
    }

    /**
     * Resets a completed/failed session back to idle.
     */
    fun resetTask() {
        if (sessionJob?.isActive == true) {
            Logger.w(TAG, "Cannot reset task state while a session is active")
            return
        }

        Logger.i(TAG, "Resetting task state")
        instructionQueue.clear()
        _runtimeInstructions.value = emptyList()
        _taskState.value = TaskExecutionState()
        _steps.value = emptyList()
        currentRoundNumber = 0
        currentRepeatConfig = RepeatTaskConfig()
    }

    private fun clearRuntimeInstructionsForNewRound() {
        instructionQueue.clear()
        _runtimeInstructions.value = emptyList()
        updateInstructionCounts()
    }

    // endregion

    // region RuntimeInstructionSource

    override fun consumeImmediateInstructions(applyAtStep: Int): List<RuntimeInstruction> {
        val pending = instructionQueue.drainImmediateInstructions()
        if (pending.isEmpty()) return emptyList()

        val applied =
            pending.map { instruction ->
                instruction.copy(
                    status = RuntimeInstructionStatus.APPLIED,
                    appliedAtStep = applyAtStep,
                )
            }

        applied.forEach { instruction ->
            replaceInstruction(instruction)
            getHistoryManager()?.recordInstructionApplied(instruction)
        }
        updateInstructionCounts()
        return applied
    }

    override fun consumeNextStep(applyAtStep: Int): RuntimeInstruction? {
        val pending = instructionQueue.pollNextStep() ?: return null
        val executing =
            pending.copy(
                status = RuntimeInstructionStatus.EXECUTING,
                appliedAtStep = applyAtStep,
            )

        replaceInstruction(executing)
        getHistoryManager()?.recordInstructionApplied(executing)
        updateInstructionCounts()
        return executing
    }

    override fun markNextStepCompleted(
        instructionId: String,
        completedAtStep: Int,
    ) {
        val current =
            _runtimeInstructions.value.firstOrNull { it.id == instructionId }
                ?: return

        val completed =
            current.copy(
                status = RuntimeInstructionStatus.COMPLETED,
                completedAtStep = completedAtStep,
            )

        replaceInstruction(completed)
        getHistoryManager()?.recordInstructionCompleted(completed)
        updateInstructionCounts()
    }

    private fun replaceInstruction(updated: RuntimeInstruction) {
        _runtimeInstructions.value =
            _runtimeInstructions.value.map { current ->
                if (current.id == updated.id) updated else current
            }
    }

    private fun updateInstructionCounts() {
        val instructions = _runtimeInstructions.value
        val pendingImmediate =
            instructions.count {
                it.mode == RuntimeInstructionMode.CONTINUE_CURRENT &&
                    it.status == RuntimeInstructionStatus.PENDING
            }
        val pendingNext =
            instructions.count {
                it.mode == RuntimeInstructionMode.NEXT_STEP &&
                    it.status == RuntimeInstructionStatus.PENDING
            }

        _taskState.value =
            _taskState.value.copy(
                pendingInstructionCount = pendingImmediate,
                queuedNextStepCount = pendingNext,
            )
    }

    // endregion

    // region Query methods

    enum class StartTaskBlockReason {
        NONE,
        SERVICE_NOT_CONNECTED,
        PHONE_AGENT_NULL,
        TASK_ALREADY_RUNNING,
    }

    fun canStartTask(): Boolean = getStartTaskBlockReason() == StartTaskBlockReason.NONE

    fun getStartTaskBlockReason(): StartTaskBlockReason {
        if (sessionJob?.isActive == true) {
            Logger.d(TAG, "getStartTaskBlockReason: task session already active")
            return StartTaskBlockReason.TASK_ALREADY_RUNNING
        }

        val componentManager =
            getComponentManager()
                ?: return StartTaskBlockReason.SERVICE_NOT_CONNECTED

        if (!componentManager.isServiceConnected) {
            Logger.d(TAG, "getStartTaskBlockReason: service not connected")
            return StartTaskBlockReason.SERVICE_NOT_CONNECTED
        }

        val agent = componentManager.phoneAgent
        if (agent == null) {
            Logger.d(TAG, "getStartTaskBlockReason: phoneAgent is null")
            return StartTaskBlockReason.PHONE_AGENT_NULL
        }

        if (agent.isRunning() || agent.isPaused()) {
            Logger.d(TAG, "getStartTaskBlockReason: task already running or paused")
            return StartTaskBlockReason.TASK_ALREADY_RUNNING
        }

        return StartTaskBlockReason.NONE
    }

    fun isTaskRunning(): Boolean {
        val status = _taskState.value.status
        return status == TaskStatus.RUNNING ||
            status == TaskStatus.PAUSED ||
            status == TaskStatus.WAITING_REPEAT
    }

    // endregion

    // region PhoneAgentListener

    override fun onStepStarted(stepNumber: Int) {
        Logger.d(TAG, "Step $stepNumber started")
        _taskState.value =
            _taskState.value.copy(
                status = TaskStatus.RUNNING,
                stepNumber = stepNumber,
                thinking = "",
                currentAction = "",
            )

        val newStep =
            TaskStep(
                stepNumber = stepNumber,
                thinking = "",
                action = "",
            )
        _steps.value = _steps.value + newStep
    }

    override fun onThinkingUpdate(thinking: String) {
        Logger.d(TAG, "Thinking update: ${thinking.take(50)}...")
        _taskState.value = _taskState.value.copy(thinking = thinking)

        val currentSteps = _steps.value.toMutableList()
        if (currentSteps.isNotEmpty()) {
            val lastIndex = currentSteps.lastIndex
            currentSteps[lastIndex] = currentSteps[lastIndex].copy(thinking = thinking)
            _steps.value = currentSteps
        }
    }

    override fun onActionExecuted(action: AgentAction) {
        val actionText = action.formatForDisplay()
        Logger.d(TAG, "Action executed: $actionText")
        _taskState.value = _taskState.value.copy(currentAction = actionText)

        val currentSteps = _steps.value.toMutableList()
        if (currentSteps.isNotEmpty()) {
            val lastIndex = currentSteps.lastIndex
            currentSteps[lastIndex] = currentSteps[lastIndex].copy(action = actionText)
            _steps.value = currentSteps
        }
    }

    override fun onTaskCompleted(message: String) {
        if (sessionJob?.isActive == true) {
            Logger.d(TAG, "Ignoring Agent completion callback; session orchestrator owns completion")
            return
        }

        Logger.i(TAG, "Task completed: $message")
        _taskState.value =
            _taskState.value.copy(
                status = TaskStatus.COMPLETED,
                resultMessage = message,
            )
        FloatingWindowStateManager.onTaskCompleted()
    }

    override fun onTaskFailed(error: String) {
        if (sessionJob?.isActive == true) {
            Logger.d(TAG, "Ignoring Agent failure callback; session orchestrator owns completion")
            return
        }

        Logger.e(TAG, "Task failed: $error")
        _taskState.value =
            _taskState.value.copy(
                status = TaskStatus.FAILED,
                resultMessage = error,
            )
        FloatingWindowStateManager.onTaskCompleted()
    }

    override fun onScreenshotStarted() {
        Logger.d(TAG, "Screenshot started")
    }

    override fun onScreenshotCompleted() {
        Logger.d(TAG, "Screenshot completed")
    }

    override fun onFloatingWindowRefreshNeeded() {
        Logger.d(TAG, "Floating window refresh needed")
    }

    override fun onTaskPaused(stepNumber: Int) {
        Logger.i(TAG, "Task paused at step $stepNumber")
        _taskState.value = _taskState.value.copy(status = TaskStatus.PAUSED)
    }

    override fun onTaskResumed(stepNumber: Int) {
        Logger.i(TAG, "Task resumed from step $stepNumber")
        _taskState.value = _taskState.value.copy(status = TaskStatus.RUNNING)
    }

    // endregion

    // region State mapping utilities

    fun mapAgentStateToTaskStatus(agentState: AgentState): TaskStatus = when (agentState) {
        AgentState.IDLE -> TaskStatus.IDLE
        AgentState.RUNNING -> TaskStatus.RUNNING
        AgentState.PAUSED -> TaskStatus.PAUSED
        AgentState.CANCELLED -> TaskStatus.FAILED
    }

    // endregion
}
