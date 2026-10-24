package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush

/**
 * Animated Shimmer Box for loading skeletons.
 * Renders in the Draw phase (drawBehind) rather than Composition phase,
 * ensuring zero recompositions on animation ticks while preserving
 * the exact visual gradient and linear translation.
 */
@Composable
fun CardShimmer(modifier: Modifier = Modifier) {
    Box(modifier = modifier.shimmerEffect())
}

fun Modifier.shimmerEffect(): Modifier = composed {
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val shimmerColors = remember(surfaceVariant, primary) {
        listOf(
            surfaceVariant.copy(alpha = 0.6f),
            primary.copy(alpha = 0.2f),
            surfaceVariant.copy(alpha = 0.6f)
        )
    }

    val transition = rememberInfiniteTransition(label = "shimmer_transition")
    val translateAnimation = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )

    drawBehind {
        val progress = translateAnimation.value
        drawRect(
            brush = Brush.linearGradient(
                colors = shimmerColors,
                start = Offset(x = progress - 600f, y = progress - 600f),
                end = Offset(x = progress, y = progress)
            )
        )
    }
}
