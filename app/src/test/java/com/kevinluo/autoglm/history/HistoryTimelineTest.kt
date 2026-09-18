package com.kevinluo.autoglm.history

import com.kevinluo.autoglm.task.RuntimeInstructionMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class HistoryTimelineTest :
    StringSpec({
        "v2 timeline keeps user instruction between surrounding Agent steps" {
            val task =
                TaskHistory(
                    taskDescription = "test",
                    steps =
                    mutableListOf(
                        HistoryStep(
                            stepNumber = 1,
                            roundNumber = 1,
                            timestamp = 100L,
                            thinking = "",
                            action = null,
                            actionDescription = "step 1",
                            screenshotPath = null,
                            annotatedScreenshotPath = null,
                            success = true,
                        ),
                        HistoryStep(
                            stepNumber = 2,
                            roundNumber = 1,
                            timestamp = 300L,
                            thinking = "",
                            action = null,
                            actionDescription = "step 2",
                            screenshotPath = null,
                            annotatedScreenshotPath = null,
                            success = true,
                        ),
                    ),
                    events =
                    mutableListOf(
                        HistoryEvent.RoundStarted(
                            sequence = 1,
                            timestamp = 50L,
                            roundNumber = 1,
                        ),
                        HistoryEvent.AgentStepRecorded(
                            sequence = 2,
                            timestamp = 100L,
                            roundNumber = 1,
                            stepNumber = 1,
                        ),
                        HistoryEvent.UserInstructionAdded(
                            sequence = 3,
                            timestamp = 200L,
                            roundNumber = 1,
                            instructionId = "instruction",
                            content = "change target",
                            mode = RuntimeInstructionMode.CONTINUE_CURRENT,
                            addedAtStep = 1,
                        ),
                        HistoryEvent.UserInstructionApplied(
                            sequence = 4,
                            timestamp = 210L,
                            roundNumber = 1,
                            instructionId = "instruction",
                            appliedAtStep = 2,
                        ),
                        HistoryEvent.AgentStepRecorded(
                            sequence = 5,
                            timestamp = 300L,
                            roundNumber = 1,
                            stepNumber = 2,
                        ),
                    ),
                )

            val timeline = buildHistoryTimeline(task)

            (timeline[0] is HistoryTimelineItem.Header) shouldBe true
            (timeline[1] is HistoryTimelineItem.RoundHeader) shouldBe true
            (timeline[2] is HistoryTimelineItem.AgentStepItem) shouldBe true
            (timeline[3] is HistoryTimelineItem.UserInstructionItem) shouldBe true
            (timeline[4] is HistoryTimelineItem.AgentStepItem) shouldBe true

            val instruction = timeline[3] as HistoryTimelineItem.UserInstructionItem
            instruction.appliedAtStep shouldBe 2
        }

        "legacy history without events still exposes every stored step" {
            val task =
                TaskHistory(
                    schemaVersion = 1,
                    taskDescription = "legacy",
                    steps =
                    mutableListOf(
                        HistoryStep(
                            stepNumber = 1,
                            thinking = "",
                            action = null,
                            actionDescription = "one",
                            screenshotPath = null,
                            annotatedScreenshotPath = null,
                            success = true,
                        ),
                        HistoryStep(
                            stepNumber = 2,
                            thinking = "",
                            action = null,
                            actionDescription = "two",
                            screenshotPath = null,
                            annotatedScreenshotPath = null,
                            success = true,
                        ),
                    ),
                )

            val timeline = buildHistoryTimeline(task)

            timeline.count { it is HistoryTimelineItem.AgentStepItem } shouldBe 2
        }
    })
