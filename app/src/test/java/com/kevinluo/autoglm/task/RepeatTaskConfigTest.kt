package com.kevinluo.autoglm.task

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

class RepeatTaskConfigTest :
    StringSpec({
        "5 to 10 minutes is a valid repeat range" {
            RepeatTaskConfig(
                enabled = true,
                minMinutes = 5,
                maxMinutes = 10,
            ).isValid() shouldBe true
        }

        "minimum interval must be greater than zero" {
            RepeatTaskConfig(
                enabled = true,
                minMinutes = 0,
                maxMinutes = 10,
            ).validationError() shouldBe RepeatConfigError.MIN_MUST_BE_POSITIVE
        }

        "maximum interval must not be below minimum" {
            RepeatTaskConfig(
                enabled = true,
                minMinutes = 10,
                maxMinutes = 5,
            ).validationError() shouldBe RepeatConfigError.MAX_LESS_THAN_MIN
        }

        "random delay uses the complete second-level inclusive range" {
            val config =
                RepeatTaskConfig(
                    enabled = true,
                    minMinutes = 5,
                    maxMinutes = 10,
                )
            val random = Random(20260918)
            val values = List(1000) { config.randomDelaySeconds(random) }

            values.all { it in 300L..600L } shouldBe true
            values.any { it % 60L != 0L } shouldBe true
        }

        "equal minimum and maximum still produces a valid exact-second delay" {
            val config =
                RepeatTaskConfig(
                    enabled = true,
                    minMinutes = 5,
                    maxMinutes = 5,
                )

            config.randomDelaySeconds(Random(1)) shouldBe 300L
        }
    })
