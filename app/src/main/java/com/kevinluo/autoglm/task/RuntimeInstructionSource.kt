package com.kevinluo.autoglm.task

sealed class RoundFinishResolution {
    data class ApplyImmediate(
        val instructions: List<RuntimeInstruction>,
    ) : RoundFinishResolution()

    data class ExecuteNext(
        val instruction: RuntimeInstruction,
    ) : RoundFinishResolution()

    data object Finish : RoundFinishResolution()
}

/**
 * Boundary between [com.kevinluo.autoglm.agent.PhoneAgent] and task-session orchestration.
 *
 * PhoneAgent only consumes instructions at safe step boundaries. The implementation is responsible
 * for updating instruction lifecycle state and recording history events.
 */
interface RuntimeInstructionSource {
    /**
     * Consumes all CONTINUE_CURRENT instructions that should apply at [applyAtStep].
     */
    fun consumeImmediateInstructions(applyAtStep: Int): List<RuntimeInstruction>

    /**
     * Consumes the next queued NEXT_STEP instruction when the current stage has finished.
     */
    fun consumeNextStep(applyAtStep: Int): RuntimeInstruction?

    /**
     * Marks a queued NEXT_STEP instruction complete after its stage reaches Finish.
     */
    fun markNextStepCompleted(
        instructionId: String,
        completedAtStep: Int,
    )

    /**
     * Atomically resolves a stage Finish against instructions that may be added concurrently.
     *
     * If no instruction was accepted before this call, the round is closed for further input and
     * [RoundFinishResolution.Finish] is returned. Any later UI submission must be rejected.
     */
    fun resolveRoundFinish(applyAtStep: Int): RoundFinishResolution
}
