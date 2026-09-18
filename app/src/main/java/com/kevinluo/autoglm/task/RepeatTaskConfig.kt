package com.kevinluo.autoglm.task

import kotlin.random.Random

/**
 * Configuration for repeating a complete task session round.
 *
 * The first round always starts immediately. When repetition is enabled, a new delay is
 * generated after each successful round. Delays are generated in whole seconds so a
 * 5-10 minute range means any value from 300 through 600 seconds, inclusive.
 */
data class RepeatTaskConfig(
    val enabled: Boolean = false,
    val minMinutes: Int = DEFAULT_MIN_MINUTES,
    val maxMinutes: Int = DEFAULT_MAX_MINUTES,
) {
    /**
     * Returns whether the configured range can be used for scheduling.
     */
    fun isValid(): Boolean = validationError() == null

    /**
     * Returns the first validation error, or null when the configuration is valid.
     */
    fun validationError(): RepeatConfigError? = when {
        minMinutes <= 0 -> RepeatConfigError.MIN_MUST_BE_POSITIVE
        maxMinutes < minMinutes -> RepeatConfigError.MAX_LESS_THAN_MIN
        else -> null
    }

    /**
     * Generates a random delay in seconds using the inclusive configured minute range.
     *
     * Example: 5-10 minutes becomes 300-600 seconds, inclusive.
     *
     * @throws IllegalArgumentException if the configuration range is invalid
     */
    fun randomDelaySeconds(random: Random = Random.Default): Long {
        require(isValid()) {
            "Invalid repeat range: minMinutes=$minMinutes, maxMinutes=$maxMinutes"
        }

        val minSeconds = minMinutes.toLong() * SECONDS_PER_MINUTE
        val maxSeconds = maxMinutes.toLong() * SECONDS_PER_MINUTE

        return random.nextLong(
            from = minSeconds,
            until = maxSeconds + 1,
        )
    }

    companion object {
        const val DEFAULT_MIN_MINUTES = 5
        const val DEFAULT_MAX_MINUTES = 10
        private const val SECONDS_PER_MINUTE = 60L
    }
}

/**
 * Validation errors for [RepeatTaskConfig].
 */
enum class RepeatConfigError {
    MIN_MUST_BE_POSITIVE,
    MAX_LESS_THAN_MIN,
}
