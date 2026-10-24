package com.example.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs

fun Modifier.swipeToNavigate(
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    threshold: Float = 150f
): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var totalDrag = 0f
        var isConsumedByChild = false
        
        do {
            val event = awaitPointerEvent(PointerEventPass.Final)
            val change = event.changes.firstOrNull()
            
            if (change != null) {
                if (change.isConsumed) {
                    isConsumedByChild = true
                } else {
                    totalDrag += change.positionChange().x
                }
            }
        } while (event.changes.any { it.pressed })
        
        if (!isConsumedByChild) {
            if (totalDrag > threshold) {
                onSwipeRight()
            } else if (totalDrag < -threshold) {
                onSwipeLeft()
            }
        }
    }
}
