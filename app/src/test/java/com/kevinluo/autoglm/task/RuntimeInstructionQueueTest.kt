package com.kevinluo.autoglm.task

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class RuntimeInstructionQueueTest :
    StringSpec({
        "continue-current instructions drain in FIFO order" {
            val queue = RuntimeInstructionQueue()
            val first =
                RuntimeInstruction(
                    id = "a",
                    content = "first",
                    mode = RuntimeInstructionMode.CONTINUE_CURRENT,
                    createdAt = 1L,
                    roundNumber = 1,
                    addedAtStep = 2,
                )
            val second =
                RuntimeInstruction(
                    id = "b",
                    content = "second",
                    mode = RuntimeInstructionMode.CONTINUE_CURRENT,
                    createdAt = 2L,
                    roundNumber = 1,
                    addedAtStep = 2,
                )

            queue.add(first)
            queue.add(second)

            queue.drainImmediateInstructions().map { it.id } shouldBe listOf("a", "b")
            queue.immediateCount() shouldBe 0
        }

        "next-step instructions are consumed one by one in FIFO order" {
            val queue = RuntimeInstructionQueue()
            listOf("a", "b", "c").forEachIndexed { index, id ->
                queue.add(
                    RuntimeInstruction(
                        id = id,
                        content = "step $id",
                        mode = RuntimeInstructionMode.NEXT_STEP,
                        createdAt = index.toLong(),
                        roundNumber = 1,
                        addedAtStep = 3,
                    ),
                )
            }

            queue.pollNextStep()?.id shouldBe "a"
            queue.pollNextStep()?.id shouldBe "b"
            queue.pollNextStep()?.id shouldBe "c"
            queue.pollNextStep() shouldBe null
        }

        "clear removes both instruction modes" {
            val queue = RuntimeInstructionQueue()
            queue.add(
                RuntimeInstruction(
                    content = "continue",
                    mode = RuntimeInstructionMode.CONTINUE_CURRENT,
                    roundNumber = 1,
                    addedAtStep = 1,
                ),
            )
            queue.add(
                RuntimeInstruction(
                    content = "next",
                    mode = RuntimeInstructionMode.NEXT_STEP,
                    roundNumber = 1,
                    addedAtStep = 1,
                ),
            )

            queue.clear()

            queue.immediateCount() shouldBe 0
            queue.nextStepCount() shouldBe 0
            queue.snapshot() shouldBe emptyList()
        }
    })
