package com.kevinluo.autoglm.task

import java.util.ArrayDeque

/**
 * Thread-safe in-memory queues for instructions added while an Agent round is running.
 *
 * CONTINUE_CURRENT instructions are consumed together at the next safe step boundary.
 * NEXT_STEP instructions are consumed one-by-one in FIFO order when the current stage finishes.
 */
class RuntimeInstructionQueue {
    private val lock = Any()
    private val immediateQueue = ArrayDeque<RuntimeInstruction>()
    private val nextStepQueue = ArrayDeque<RuntimeInstruction>()

    /**
     * Adds an instruction to the queue associated with its mode.
     */
    fun add(instruction: RuntimeInstruction) {
        require(instruction.content.isNotBlank()) {
            "Runtime instruction content must not be blank"
        }
        require(instruction.status == RuntimeInstructionStatus.PENDING) {
            "Only pending runtime instructions can be queued"
        }

        synchronized(lock) {
            when (instruction.mode) {
                RuntimeInstructionMode.CONTINUE_CURRENT -> immediateQueue.addLast(instruction)
                RuntimeInstructionMode.NEXT_STEP -> nextStepQueue.addLast(instruction)
            }
        }
    }

    /**
     * Returns true when at least one CONTINUE_CURRENT instruction is waiting.
     */
    fun hasImmediateInstructions(): Boolean = synchronized(lock) {
        immediateQueue.isNotEmpty()
    }

    /**
     * Removes and returns all CONTINUE_CURRENT instructions in FIFO order.
     */
    fun drainImmediateInstructions(): List<RuntimeInstruction> = synchronized(lock) {
        buildList {
            while (immediateQueue.isNotEmpty()) {
                add(immediateQueue.removeFirst())
            }
        }
    }

    /**
     * Removes and returns the next queued follow-up instruction, or null when none remain.
     */
    fun pollNextStep(): RuntimeInstruction? = synchronized(lock) {
        if (nextStepQueue.isEmpty()) null else nextStepQueue.removeFirst()
    }

    /**
     * Returns the number of pending CONTINUE_CURRENT instructions.
     */
    fun immediateCount(): Int = synchronized(lock) {
        immediateQueue.size
    }

    /**
     * Returns the number of pending NEXT_STEP instructions.
     */
    fun nextStepCount(): Int = synchronized(lock) {
        nextStepQueue.size
    }

    /**
     * Returns a read-only snapshot of all currently queued instructions.
     *
     * The snapshot is ordered by creation time for UI display only. Consumption order remains
     * FIFO within each queue.
     */
    fun snapshot(): List<RuntimeInstruction> = synchronized(lock) {
        (immediateQueue.toList() + nextStepQueue.toList())
            .sortedBy { it.createdAt }
    }

    /**
     * Clears all pending runtime instructions.
     */
    fun clear() {
        synchronized(lock) {
            immediateQueue.clear()
            nextStepQueue.clear()
        }
    }
}
