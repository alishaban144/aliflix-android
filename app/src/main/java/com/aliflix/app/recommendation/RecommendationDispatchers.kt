package com.aliflix.app.recommendation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Shared execution contexts for catalogue, metadata, and Worker clients. */
data class RecommendationDispatchers(
    val io: CoroutineDispatcher,
    val computation: CoroutineDispatcher,
) {
    companion object {
        val Default = RecommendationDispatchers(
            io = Dispatchers.IO,
            computation = Dispatchers.Default,
        )
    }
}
