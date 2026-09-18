package com.kevinluo.autoglm.history

/**
 * UI/export-friendly timeline built from persisted task steps and history events.
 */
sealed class HistoryTimelineItem {
    data class Header(val task: TaskHistory) : HistoryTimelineItem()

    data class RoundHeader(
        val roundNumber: Int,
        val timestamp: Long,
    ) : HistoryTimelineItem()

    data class AgentStepItem(
        val step: HistoryStep,
    ) : HistoryTimelineItem()

    data class UserInstructionItem(
        val event: HistoryEvent.UserInstructionAdded,
        val appliedAtStep: Int?,
        val completedAtStep: Int?,
    ) : HistoryTimelineItem()

    data class RoundCompletedItem(
        val event: HistoryEvent.RoundCompleted,
    ) : HistoryTimelineItem()

    data class RepeatScheduledItem(
        val event: HistoryEvent.RepeatScheduled,
    ) : HistoryTimelineItem()
}

/**
 * Builds a stable, human-readable execution timeline.
 *
 * Schema-v2 histories use event sequence as the authoritative ordering. Legacy histories that do
 * not contain events fall back to their stored step order.
 */
fun buildHistoryTimeline(task: TaskHistory): List<HistoryTimelineItem> {
    val items = mutableListOf<HistoryTimelineItem>()
    items += HistoryTimelineItem.Header(task)

    if (task.events.isEmpty()) {
        appendLegacySteps(task, items)
        return items
    }

    val appliedByInstruction =
        task.events
            .filterIsInstance<HistoryEvent.UserInstructionApplied>()
            .associateBy { it.instructionId }
    val completedByInstruction =
        task.events
            .filterIsInstance<HistoryEvent.UserInstructionCompleted>()
            .associateBy { it.instructionId }

    val stepsByKey =
        task.steps.associateBy { step ->
            step.roundNumber to step.stepNumber
        }
    val emittedStepKeys = mutableSetOf<Pair<Int, Int>>()

    task.events
        .sortedBy { it.sequence }
        .forEach { event ->
            when (event) {
                is HistoryEvent.RoundStarted -> {
                    items +=
                        HistoryTimelineItem.RoundHeader(
                            roundNumber = event.roundNumber,
                            timestamp = event.timestamp,
                        )
                }

                is HistoryEvent.AgentStepRecorded -> {
                    val key = event.roundNumber to event.stepNumber
                    stepsByKey[key]?.let { step ->
                        items += HistoryTimelineItem.AgentStepItem(step)
                        emittedStepKeys += key
                    }
                }

                is HistoryEvent.UserInstructionAdded -> {
                    items +=
                        HistoryTimelineItem.UserInstructionItem(
                            event = event,
                            appliedAtStep = appliedByInstruction[event.instructionId]?.appliedAtStep,
                            completedAtStep =
                            completedByInstruction[event.instructionId]?.completedAtStep,
                        )
                }

                is HistoryEvent.RoundCompleted -> {
                    items += HistoryTimelineItem.RoundCompletedItem(event)
                }

                is HistoryEvent.RepeatScheduled -> {
                    items += HistoryTimelineItem.RepeatScheduledItem(event)
                }

                is HistoryEvent.UserInstructionApplied,
                is HistoryEvent.UserInstructionCompleted -> {
                    // Folded into UserInstructionItem above.
                }
            }
        }

    // Be defensive with partially migrated/corrupted v2 history: never hide a recorded Agent step.
    task.steps
        .filter { (it.roundNumber to it.stepNumber) !in emittedStepKeys }
        .sortedWith(compareBy<HistoryStep> { it.timestamp }.thenBy { it.stepNumber })
        .forEach { step ->
            items += HistoryTimelineItem.AgentStepItem(step)
        }

    return items
}

private fun appendLegacySteps(
    task: TaskHistory,
    items: MutableList<HistoryTimelineItem>,
) {
    val rounds = task.steps.groupBy { it.roundNumber }.toSortedMap()
    val showRoundHeader = rounds.size > 1

    rounds.forEach { (roundNumber, steps) ->
        if (showRoundHeader) {
            items +=
                HistoryTimelineItem.RoundHeader(
                    roundNumber = roundNumber,
                    timestamp = steps.minOfOrNull { it.timestamp } ?: task.startTime,
                )
        }

        steps
            .sortedWith(compareBy<HistoryStep> { it.timestamp }.thenBy { it.stepNumber })
            .forEach { step ->
                items += HistoryTimelineItem.AgentStepItem(step)
            }
    }
}
