package com.glosso.server.generation

/** The measured shortfall of one Need Class against the configured pool size. */
data class Gap(val deficit: Int, val state: GapState)

/**
 * Measures how far a Need Class is from its target. It only measures: it never
 * triggers generation, which is a later phase. `maximum` is a cap for phase 2,
 * not a trigger today.
 */
object GapCalculator {

    fun compute(availableEffective: Int, pool: PoolConfig): Gap {
        val effective = availableEffective.coerceAtLeast(0)
        val deficit = (pool.target - effective).coerceAtLeast(0)
        val state = when {
            effective >= pool.maximum -> GapState.AT_MAX
            effective < pool.minimum -> GapState.BELOW_MIN
            effective < pool.target -> GapState.BELOW_TARGET
            else -> GapState.OK
        }
        return Gap(deficit, state)
    }

    /** True when the class is short enough to be a phase 2 generation candidate. */
    fun isCandidate(gap: Gap): Boolean = gap.state == GapState.BELOW_MIN
}
