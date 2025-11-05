package com.exo.android.node.inference

import com.exo.android.node.data.Shard
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Dummy inference engine for testing without real models
 * Mirrors Python DummyInferenceEngine from exo/inference/dummy_inference_engine.py
 */
class DummyInferenceEngine : InferenceEngine {
    private val loadedShards = mutableSetOf<String>()

    override suspend fun encode(shard: Shard, prompt: String): FloatArray {
        Timber.d("Dummy encode: $prompt")
        // Return simple token representation
        return prompt.split(" ").mapIndexed { index, _ -> index.toFloat() }.toFloatArray()
    }

    override suspend fun decode(shard: Shard, tokens: FloatArray): String {
        Timber.d("Dummy decode: ${tokens.size} tokens")
        // Return dummy text
        return tokens.joinToString(" ") { "token_${it.toInt()}" }
    }

    override suspend fun sample(logits: FloatArray, temperature: Float): FloatArray {
        Timber.d("Dummy sample from ${logits.size} logits")
        // Just return first token
        return floatArrayOf(0f)
    }

    override suspend fun inferTensor(
        requestId: String,
        shard: Shard,
        inputData: FloatArray,
        inferenceState: InferenceState?
    ): Pair<FloatArray, InferenceState?> {
        Timber.d("Dummy infer_tensor for request $requestId on shard $shard")

        // Simulate inference delay
        delay(50)

        // Return same data (pass-through)
        return inputData to null
    }

    override suspend fun loadCheckpoint(shard: Shard, modelPath: String) {
        Timber.i("Dummy load checkpoint for shard $shard from $modelPath")
        delay(100) // Simulate load time
        loadedShards.add(getShardKey(shard))
    }

    override suspend fun clearSession() {
        Timber.d("Dummy clear session")
        loadedShards.clear()
    }

    override fun getSupportedModels(): List<String> {
        return listOf("dummy", "test")
    }

    private fun getShardKey(shard: Shard): String {
        return "${shard.modelId}_${shard.startLayer}_${shard.endLayer}"
    }
}
