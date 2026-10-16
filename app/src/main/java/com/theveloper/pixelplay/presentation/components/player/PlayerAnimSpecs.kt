package com.theveloper.pixelplay.presentation.components.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp

// Shared spec for "rest pose" transitions that fire when playback pauses
// (album-art shrink, play-button corner morph). The duration is short enough
// that the animation finishes before a user can plausibly start a sheet
// expand/collapse gesture — otherwise the still-animating graphicsLayer
// invalidates draw layers mid-gesture and drops frames.
private const val PAUSE_REST_DURATION_MS = 220

internal val PauseRestFloatSpec = tween<Float>(durationMillis = PAUSE_REST_DURATION_MS, easing = FastOutSlowInEasing)
internal val PauseRestDpSpec = tween<Dp>(durationMillis = PAUSE_REST_DURATION_MS, easing = FastOutSlowInEasing)
