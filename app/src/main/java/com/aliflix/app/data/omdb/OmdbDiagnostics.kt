package com.aliflix.app.data.omdb

import java.util.concurrent.atomic.AtomicInteger

object OmdbDiagnostics {
    val omdbAndroidCacheHits = AtomicInteger(0)
    val omdbWorkerCacheHits = AtomicInteger(0)
    val omdbUpstreamRequests = AtomicInteger(0)
    val omdbVerified = AtomicInteger(0)
    val omdbNotFound = AtomicInteger(0)
    val omdbTimeouts = AtomicInteger(0)
    val omdbQuotaFailures = AtomicInteger(0)

    // Recommendation specific diagnostics
    val rawCandidates = AtomicInteger(0)
    val prefilteredCandidates = AtomicInteger(0)
    val omdbCandidatesRequested = AtomicInteger(0)
    val omdbBatchCalls = AtomicInteger(0)
    val geminiVerified = AtomicInteger(0)
    val finalResults = AtomicInteger(0)

    fun reset() {
        omdbAndroidCacheHits.set(0)
        omdbWorkerCacheHits.set(0)
        omdbUpstreamRequests.set(0)
        omdbVerified.set(0)
        omdbNotFound.set(0)
        omdbTimeouts.set(0)
        omdbQuotaFailures.set(0)
        rawCandidates.set(0)
        prefilteredCandidates.set(0)
        omdbCandidatesRequested.set(0)
        omdbBatchCalls.set(0)
        geminiVerified.set(0)
        finalResults.set(0)
    }

    fun dumpString(): String =
        "OMDb Diag [AndroidCacheHits: ${omdbAndroidCacheHits.get()}, Verified: ${omdbVerified.get()}, NotFound: ${omdbNotFound.get()}, Timeouts: ${omdbTimeouts.get()}, QuotaFailures: ${omdbQuotaFailures.get()}, CandidatesRequested: ${omdbCandidatesRequested.get()}, GeminiVerified: ${geminiVerified.get()}]"
}
