package com.exo.android.node.inference

import com.exo.android.node.data.Shard

/**
 * Inference state for KV caching and session management
 */
data class InferenceState(
    val kvCache: Map<String, Any>? = null,
    val metadata: Map<String, Any>? = null
)

/**
 * Abstract inference engine interface
 * Mirrors Python InferenceEngine from exo/inference/inference_engine.py:11-51
 */
interface InferenceEngine {
    /**
     * Encode text prompt to token IDs
     * @param shard The model shard assignment
     * @param prompt The text input
     * @return Array of token IDs as floats
     */
    suspend fun encode(shard: Shard, prompt: String): FloatArray

    /**
     * Sample next token from logits (argmax or temperature-based)
     * @param logits The output logits from model
     * @return Sampled token ID
     */
    suspend fun sample(logits: FloatArray, temperature: Float = 0.0f): FloatArray

    /**
     * Decode token IDs back to text
     * @param shard The model shard
     * @param tokens Token IDs to decode
     * @return Decoded text string
     */
    suspend fun decode(shard: Shard, tokens: FloatArray): String

    /**
     * Run inference on tensor data through this shard's layers
     * @param requestId Unique request identifier
     * @param shard Model layers this node handles
     * @param inputData Input tensor data
     * @param inferenceState Optional cached state (KV cache, etc.)
     * @return Output tensor and updated inference state
     */
    suspend fun inferTensor(
        requestId: String,
        shard: Shard,
        inputData: FloatArray,
        inferenceState: InferenceState? = null
    ): Pair<FloatArray, InferenceState?>

    /**
     * Load model weights for a specific shard
     * @param shard The shard to load
     * @param modelPath Local file path to model weights
     */
    suspend fun loadCheckpoint(shard: Shard, modelPath: String)

    /**
     * Save model checkpoint (optional, for fine-tuning)
     * @param shard The shard to save
     * @param savePath Path to save checkpoint
     */
    suspend fun saveCheckpoint(shard: Shard, savePath: String) {
        // Optional implementation
        throw UnsupportedOperationException("saveCheckpoint not implemented")
    }

    /**
     * Convenience method: run inference on text prompt
     * Combines encode -> inferTensor
     */
    suspend fun inferPrompt(
        requestId: String,
        shard: Shard,
        prompt: String,
        inferenceState: InferenceState? = null
    ): Pair<FloatArray, InferenceState?> {
        val tokens = encode(shard, prompt)
        return inferTensor(requestId, shard, tokens, inferenceState)
    }

    /**
     * Clear any cached session data
     */
    suspend fun clearSession() {
        // Override if needed
    }

    /**
     * Get supported model architectures
     */
    fun getSupportedModels(): List<String>

    /**
     * Check if a model is supported
     */
    fun isModelSupported(modelId: String): Boolean {
        return getSupportedModels().any { modelId.contains(it, ignoreCase = true) }
    }
}

/**
 * Factory for creating inference engines
 */
object InferenceEngineFactory {
    enum class EngineType {
        TFLITE,
        ONNX,
        DUMMY
    }

    fun create(
        type: EngineType,
        context: android.content.Context
    ): InferenceEngine {
        return when (type) {
            EngineType.TFLITE -> TFLiteInferenceEngine(context)
            EngineType.ONNX -> throw NotImplementedError("ONNX engine not yet implemented")
            EngineType.DUMMY -> DummyInferenceEngine()
        }
    }
}
