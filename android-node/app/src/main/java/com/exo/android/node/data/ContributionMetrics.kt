package com.exo.android.node.data

import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks compute contribution metrics for smart contract reward distribution
 * This data can be used to prove work done and calculate rewards
 */
@Serializable
data class ContributionMetrics(
    val nodeId: String,
    val sessionId: String,
    val startTime: Long,
    var lastUpdateTime: Long,
    var totalInferenceRequests: Long = 0,
    var totalPromptRequests: Long = 0,
    var totalTensorRequests: Long = 0,
    var totalTokensProcessed: Long = 0,
    var totalComputeTimeMs: Long = 0,
    var totalBytesProcessed: Long = 0,
    var failedRequests: Long = 0,
    var peakMemoryUsageMb: Int = 0,
    var averageLatencyMs: Long = 0
) {
    fun toMap(): Map<String, Any> = mapOf(
        "node_id" to nodeId,
        "session_id" to sessionId,
        "start_time" to startTime,
        "last_update_time" to lastUpdateTime,
        "total_inference_requests" to totalInferenceRequests,
        "total_prompt_requests" to totalPromptRequests,
        "total_tensor_requests" to totalTensorRequests,
        "total_tokens_processed" to totalTokensProcessed,
        "total_compute_time_ms" to totalComputeTimeMs,
        "total_bytes_processed" to totalBytesProcessed,
        "failed_requests" to failedRequests,
        "peak_memory_usage_mb" to peakMemoryUsageMb,
        "average_latency_ms" to averageLatencyMs
    )

    /**
     * Calculate a contribution score for reward distribution
     * This is a simple formula - can be customized based on tokenomics
     */
    fun calculateContributionScore(): Double {
        val requestWeight = 1.0
        val tokenWeight = 0.1
        val computeWeight = 0.001
        val reliabilityBonus = if (failedRequests == 0L) 1.2 else 1.0

        return (
            (totalInferenceRequests * requestWeight) +
            (totalTokensProcessed * tokenWeight) +
            (totalComputeTimeMs * computeWeight)
        ) * reliabilityBonus
    }
}

/**
 * Thread-safe tracker for contribution metrics
 */
class ContributionTracker(
    private val nodeId: String,
    private val sessionId: String = generateSessionId()
) {
    private val startTime = System.currentTimeMillis()

    // Atomic counters for thread-safe updates
    private val _totalInferenceRequests = AtomicLong(0)
    private val _totalPromptRequests = AtomicLong(0)
    private val _totalTensorRequests = AtomicLong(0)
    private val _totalTokensProcessed = AtomicLong(0)
    private val _totalComputeTimeMs = AtomicLong(0)
    private val _totalBytesProcessed = AtomicLong(0)
    private val _failedRequests = AtomicLong(0)
    private val _peakMemoryUsageMb = AtomicInteger(0)
    private val _totalLatencyMs = AtomicLong(0)
    private val _latencyCount = AtomicLong(0)

    /**
     * Record a successful prompt inference request
     */
    fun recordPromptRequest(
        tokensProcessed: Int,
        computeTimeMs: Long,
        bytesProcessed: Long
    ) {
        _totalInferenceRequests.incrementAndGet()
        _totalPromptRequests.incrementAndGet()
        _totalTokensProcessed.addAndGet(tokensProcessed.toLong())
        _totalComputeTimeMs.addAndGet(computeTimeMs)
        _totalBytesProcessed.addAndGet(bytesProcessed)
        recordLatency(computeTimeMs)
    }

    /**
     * Record a successful tensor inference request
     */
    fun recordTensorRequest(
        computeTimeMs: Long,
        bytesProcessed: Long
    ) {
        _totalInferenceRequests.incrementAndGet()
        _totalTensorRequests.incrementAndGet()
        _totalComputeTimeMs.addAndGet(computeTimeMs)
        _totalBytesProcessed.addAndGet(bytesProcessed)
        recordLatency(computeTimeMs)
    }

    /**
     * Record a failed request
     */
    fun recordFailedRequest() {
        _failedRequests.incrementAndGet()
    }

    /**
     * Update peak memory usage
     */
    fun updatePeakMemory(memoryMb: Int) {
        var current = _peakMemoryUsageMb.get()
        while (memoryMb > current) {
            if (_peakMemoryUsageMb.compareAndSet(current, memoryMb)) {
                break
            }
            current = _peakMemoryUsageMb.get()
        }
    }

    /**
     * Record latency for average calculation
     */
    private fun recordLatency(latencyMs: Long) {
        _totalLatencyMs.addAndGet(latencyMs)
        _latencyCount.incrementAndGet()
    }

    /**
     * Get current metrics snapshot
     */
    fun getMetrics(): ContributionMetrics {
        val latencyCount = _latencyCount.get()
        val averageLatency = if (latencyCount > 0) {
            _totalLatencyMs.get() / latencyCount
        } else {
            0L
        }

        return ContributionMetrics(
            nodeId = nodeId,
            sessionId = sessionId,
            startTime = startTime,
            lastUpdateTime = System.currentTimeMillis(),
            totalInferenceRequests = _totalInferenceRequests.get(),
            totalPromptRequests = _totalPromptRequests.get(),
            totalTensorRequests = _totalTensorRequests.get(),
            totalTokensProcessed = _totalTokensProcessed.get(),
            totalComputeTimeMs = _totalComputeTimeMs.get(),
            totalBytesProcessed = _totalBytesProcessed.get(),
            failedRequests = _failedRequests.get(),
            peakMemoryUsageMb = _peakMemoryUsageMb.get(),
            averageLatencyMs = averageLatency
        )
    }

    /**
     * Reset all metrics (e.g., after reward distribution)
     */
    fun reset() {
        _totalInferenceRequests.set(0)
        _totalPromptRequests.set(0)
        _totalTensorRequests.set(0)
        _totalTokensProcessed.set(0)
        _totalComputeTimeMs.set(0)
        _totalBytesProcessed.set(0)
        _failedRequests.set(0)
        _peakMemoryUsageMb.set(0)
        _totalLatencyMs.set(0)
        _latencyCount.set(0)
    }

    companion object {
        private fun generateSessionId(): String {
            return "session_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
        }
    }
}
