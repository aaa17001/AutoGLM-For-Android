package com.kevinluo.autoglm.task

import java.util.UUID

/**
 * Describes how a user instruction entered while a task is running should be applied.
 */
enum class RuntimeInstructionMode {
    /**
     * Apply at the next safe Agent step boundary and continue the current task context.
     */
    CONTINUE_CURRENT,

    /**
     * Queue the instruction as a follow-up stage after the current stage reaches Finish.
     */
    NEXT_STEP,
}

/**
 * Lifecycle state for a runtime instruction.
 */
enum class RuntimeInstructionStatus {
    PENDING,
    APPLIED,
    EXECUTING,
    COMPLETED,
    CANCELLED,
}

/**
 * A user instruction injected while a task round is already running.
 *
 * @property id Stable identifier used by execution state and history events.
 * @property content User-provided instruction text.
 * @property mode Whether the instruction modifies the current task or becomes a follow-up stage.
 * @property createdAt Wall-clock timestamp when the instruction was added.
 * @property roundNumber Round in which the instruction was added.
 * @property addedAtStep Agent step that was active when the instruction was added.
 * @property status Current lifecycle state.
 * @property appliedAtStep Agent step where the instruction was first applied, if any.
 * @property completedAtStep Agent step where a queued follow-up stage completed, if any.
 */
data class RuntimeInstruction(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val mode: RuntimeInstructionMode,
    val createdAt: Long = System.currentTimeMillis(),
    val roundNumber: Int,
    val addedAtStep: Int,
    val status: RuntimeInstructionStatus = RuntimeInstructionStatus.PENDING,
    val appliedAtStep: Int? = null,
    val completedAtStep: Int? = null,
)
