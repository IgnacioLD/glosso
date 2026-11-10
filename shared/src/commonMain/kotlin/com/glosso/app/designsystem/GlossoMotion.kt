package com.glosso.app.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Motion system: two registers, "calm" (listen, read) and "pulse" (reward).
 * Always 60 fps: animate graphicsLayer/offset or color, never re-layout.
 */
object Motion {
    const val instant = 90
    const val fast = 150
    const val base = 220
    const val medium = 320
    const val slow = 520
    const val celebration = 900

    val standard: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val decelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val accelerate: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    /** Spring only for reward: a hit, a seal, a level up. */
    val reward = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow,
    )

    /** The same pulse for sizes (a page dot stretching into a pill). */
    val pulseDp = spring<androidx.compose.ui.unit.Dp>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    /** Softer pulse for the recording mic and the streak chip. */
    val pulse = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMedium,
    )
}

/**
 * True when the learner asked to reduce motion. When set, decorative animations
 * collapse to a cross-fade. Driven from the system animator duration scale.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** A duration that collapses to 0 when reduce motion is on. */
val motionDuration: Int
    @Composable @ReadOnlyComposable
    get() = if (LocalReduceMotion.current) 0 else Motion.base
