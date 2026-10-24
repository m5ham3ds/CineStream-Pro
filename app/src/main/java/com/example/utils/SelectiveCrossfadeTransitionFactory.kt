package com.example.utils

import coil.decode.DataSource
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.transition.CrossfadeTransition
import coil.transition.Transition
import coil.transition.TransitionTarget

/**
 * Selective crossfade transition factory designed to eliminate Coil crossfade animation
 * stacking during fast scrolling and flinging (PERF-AD-05).
 *
 * Architecture & Rules:
 * 1. Memory Cache Hits: Returns Transition.Factory.NONE. Renders immediately on the first frame.
 * 2. Disk Cache Hits & Local Files: Returns Transition.Factory.NONE. Renders immediately without
 *    launching multi-frame alpha interpolation on the GPU RenderThread during fast scroll.
 * 3. Network Fetches: Applies a lightweight 100ms CrossfadeTransition to smoothly transition from
 *    the placeholder/shimmer into the newly fetched remote image, preserving visual polish.
 * 4. Error Results: Returns Transition.Factory.NONE.
 */
class SelectiveCrossfadeTransitionFactory(
    val durationMillis: Int = 100,
    val preferExactIntrinsicSize: Boolean = false
) : Transition.Factory {

    override fun create(target: TransitionTarget, result: ImageResult): Transition {
        // Only animate for remote network results.
        // Memory cache hits, disk cache hits, and errors render immediately without transition stacking.
        if (result !is SuccessResult || result.dataSource != DataSource.NETWORK) {
            return Transition.Factory.NONE.create(target, result)
        }
        return CrossfadeTransition(target, result, durationMillis, preferExactIntrinsicSize)
    }
}
