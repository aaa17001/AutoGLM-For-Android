package com.kevinluo.autoglm.history

import com.kevinluo.autoglm.task.RuntimeInstructionMode

/**
 * Ordered non-visual events that form the complete execution timeline of a task session.
 *
 * [sequence] is the authoritative ordering key. Timestamps are retained for display and export,
 * but multiple events may share the same millisecond.
 */
sealed class HistoryEvent {
    abstract val sequence: Long
    abstract val timestamp: Long
    abstract val roundNumber: Int

    data class RoundStarted(
        override val sequence: Long,
        override val timestamp: Long = System.currentTimeMillis(),
        override val roundNumber: Int,
    ) : HistoryEvent()

    data class AgentStepRecorded(
        override val sequence: Long,
        override val timestamp: Long = System.currentTimeMillis(),
        override val roundNumber: Int,
        val stepNumber: Int,
    ) : HistoryEvent()

    data class UserInstructionAdded(
        override val sequence: Long,
        override val timestamp: Long = System.currentTimeMillis(),
        override val roundNumber: Int,
        val instructionId: String,
        val content: String,
        val mode: RuntimeInstructionMode,
        val addedAtStep: Int,
    ) : HistoryEvent()

    data class UserInstructionApplied(
        override val sequence: Long,
        override val timestamp: Long = System.currentTimeMillis(),
        override val roundNumber: Int,
        val instructionId: String,
        val appliedAtStep: Int,
    ) : HistoryEvent()

    data class UserInstructionCompleted(
        override val sequence: Long,
        override val timestamp: Long = System.currentTimeMillis(),
        override val roundNumber: Int,
        val instructionId: String,
        val completedAtStep: Int,
    ) : HistoryEvent()

    data class RoundCompleted(
        override val sequence: Long,
        override val timestamp: Long = System.currentTimeMillis(),
        override val roundNumber: Int,
        val success: Boolean,
        val message: String? = null,
    ) : HistoryEvent()

    data class RepeatScheduled(
        override val sequence: Long,
        override val timestamp: Long = System.currentTimeMillis(),
        override val roundNumber: Int,
        val delaySeconds: Long,
        val nextRunAtMillis: Long,
    ) : HistoryEvent()
}
