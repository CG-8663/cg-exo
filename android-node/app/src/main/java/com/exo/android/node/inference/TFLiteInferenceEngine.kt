package com.exo.android.node.inference

import android.content.Context
import com.exo.android.node.data.Shard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * TensorFlow Lite inference engine implementation
 * Provides GPU-accelerated inference on Android devices
 */
class TFLiteInferenceEngine(
    private val context: Context
) : InferenceEngine {

    private val interpreters = mutableMapOf<String, Interpreter>()
    private val gpuDelegates = mutableListOf<GpuDelegate>()

    // Simple tokenizers per model (for MVP, would use HuggingFace tokenizers in production)
    private val tokenizers = mutableMapOf<String, SimpleTokenizer>()

    companion object {
        private const val TAG = "TFLiteEngine"
        private const val USE_GPU = true
        private const val NUM_THREADS = 4
    }

    override suspend fun encode(shard: Shard, prompt: String): FloatArray =
        withContext(Dispatchers.Default) {
            val tokenizer = getOrCreateTokenizer(shard.modelId)
            val tokens = tokenizer.encode(prompt)

            Timber.d("Encoded prompt '${prompt}' to ${tokens.size} tokens")

            // Convert to float array
            tokens.map { it.toFloat() }.toFloatArray()
        }

    override suspend fun decode(shard: Shard, tokens: FloatArray): String =
        withContext(Dispatchers.Default) {
            val tokenizer = getOrCreateTokenizer(shard.modelId)
            val tokenIds = tokens.map { it.toInt() }
            val text = tokenizer.decode(tokenIds)

            Timber.d("Decoded ${tokens.size} tokens to '${text}'")
            text
        }

    override suspend fun sample(logits: FloatArray, temperature: Float): FloatArray =
        withContext(Dispatchers.Default) {
            if (temperature <= 0.0f) {
                // Greedy sampling (argmax)
                val maxIndex = logits.indices.maxByOrNull { logits[it] } ?: 0
                floatArrayOf(maxIndex.toFloat())
            } else {
                // Temperature-based sampling
                val scaledLogits = logits.map { it / temperature }
                val expLogits = scaledLogits.map { kotlin.math.exp(it) }
                val sumExp = expLogits.sum()
                val probabilities = expLogits.map { it / sumExp }

                // Sample from distribution
                val randomValue = Math.random()
                var cumSum = 0.0
                var sampledIndex = 0

                for ((index, prob) in probabilities.withIndex()) {
                    cumSum += prob
                    if (randomValue <= cumSum) {
                        sampledIndex = index
                        break
                    }
                }

                floatArrayOf(sampledIndex.toFloat())
            }
        }

    override suspend fun inferTensor(
        requestId: String,
        shard: Shard,
        inputData: FloatArray,
        inferenceState: InferenceState?
    ): Pair<FloatArray, InferenceState?> = withContext(Dispatchers.Default) {
        val interpreter = interpreters[getShardKey(shard)]
            ?: throw IllegalStateException("Model not loaded for shard: $shard")

        Timber.d("Running inference for request $requestId on shard $shard")

        try {
            // Prepare input buffer
            val inputBuffer = createInputBuffer(inputData)

            // Prepare output buffer (size depends on model architecture)
            // For simplicity, assuming same size output. In production, query model output shape.
            val outputBuffer = ByteBuffer.allocateDirect(inputData.size * 4).apply {
                order(ByteOrder.nativeOrder())
            }

            // Run inference
            interpreter.run(inputBuffer, outputBuffer)

            // Convert output buffer to float array
            outputBuffer.rewind()
            val outputData = FloatArray(inputData.size)
            outputBuffer.asFloatBuffer().get(outputData)

            Timber.d("Inference completed for request $requestId")

            outputData to null // KV caching not implemented in MVP
        } catch (e: Exception) {
            Timber.e(e, "Inference failed for request $requestId")
            throw e
        }
    }

    override suspend fun loadCheckpoint(shard: Shard, modelPath: String) =
        withContext(Dispatchers.IO) {
            val shardKey = getShardKey(shard)

            Timber.i("Loading model checkpoint for shard $shard from $modelPath")

            try {
                // Check if model file exists
                val modelFile = File(modelPath)
                if (!modelFile.exists()) {
                    throw IllegalArgumentException("Model file not found: $modelPath")
                }

                // Load model file as MappedByteBuffer
                val modelBuffer = loadModelFile(modelFile)

                // Create interpreter options
                val options = Interpreter.Options().apply {
                    setNumThreads(NUM_THREADS)

                    if (USE_GPU) {
                        try {
                            val gpuDelegate = GpuDelegate()
                            addDelegate(gpuDelegate)
                            gpuDelegates.add(gpuDelegate)
                            Timber.d("GPU delegate enabled for shard $shard")
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to enable GPU delegate, falling back to CPU")
                        }
                    }

                    // Enable NNAPI for NPU acceleration (optional)
                    // setUseNNAPI(true)
                }

                // Create interpreter
                val interpreter = Interpreter(modelBuffer, options)
                interpreters[shardKey] = interpreter

                Timber.i("Model loaded successfully for shard $shard")
            } catch (e: Exception) {
                Timber.e(e, "Failed to load model for shard $shard")
                throw e
            }
        }

    override suspend fun clearSession() {
        Timber.d("Clearing inference session")
        // Could implement KV cache clearing here
    }

    override fun getSupportedModels(): List<String> {
        return listOf(
            "llama",
            "mistral",
            "qwen",
            "deepseek"
            // Add more supported models
        )
    }

    /**
     * Clean up resources
     */
    fun close() {
        Timber.i("Closing TFLite inference engine")

        interpreters.values.forEach { it.close() }
        interpreters.clear()

        gpuDelegates.forEach { it.close() }
        gpuDelegates.clear()

        tokenizers.clear()
    }

    /**
     * Load model file as MappedByteBuffer
     */
    private fun loadModelFile(modelFile: File): MappedByteBuffer {
        return FileInputStream(modelFile).use { inputStream ->
            val fileChannel = inputStream.channel
            fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size())
        }
    }

    /**
     * Create input buffer from float array
     */
    private fun createInputBuffer(data: FloatArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(data.size * 4).apply {
            order(ByteOrder.nativeOrder())
        }
        buffer.asFloatBuffer().put(data)
        return buffer
    }

    /**
     * Get unique key for shard
     */
    private fun getShardKey(shard: Shard): String {
        return "${shard.modelId}_${shard.startLayer}_${shard.endLayer}"
    }

    /**
     * Get or create tokenizer for model
     */
    private fun getOrCreateTokenizer(modelId: String): SimpleTokenizer {
        return tokenizers.getOrPut(modelId) {
            SimpleTokenizer(modelId)
        }
    }
}

/**
 * Simple tokenizer implementation for MVP
 * In production, use HuggingFace tokenizers library
 */
class SimpleTokenizer(private val modelId: String) {
    private val vocabulary = mutableMapOf<String, Int>()
    private val reverseVocabulary = mutableMapOf<Int, String>()

    companion object {
        // Special tokens
        private const val BOS_TOKEN = "<s>"
        private const val EOS_TOKEN = "</s>"
        private const val UNK_TOKEN = "<unk>"
        private const val PAD_TOKEN = "<pad>"
    }

    init {
        // Initialize with basic vocabulary (MVP)
        // In production, load from tokenizer.json
        vocabulary[PAD_TOKEN] = 0
        vocabulary[BOS_TOKEN] = 1
        vocabulary[EOS_TOKEN] = 2
        vocabulary[UNK_TOKEN] = 3

        // Build reverse vocabulary
        vocabulary.forEach { (token, id) ->
            reverseVocabulary[id] = token
        }

        Timber.d("Initialized simple tokenizer for $modelId with ${vocabulary.size} tokens")
    }

    /**
     * Encode text to token IDs
     * Simplified implementation - just splits on whitespace
     */
    fun encode(text: String): List<Int> {
        val tokens = mutableListOf<Int>()

        // Add BOS token
        tokens.add(vocabulary[BOS_TOKEN]!!)

        // Simple word-level tokenization (for MVP)
        text.split(" ").forEach { word ->
            val tokenId = vocabulary.getOrPut(word) {
                val newId = vocabulary.size
                reverseVocabulary[newId] = word
                newId
            }
            tokens.add(tokenId)
        }

        // Add EOS token
        tokens.add(vocabulary[EOS_TOKEN]!!)

        return tokens
    }

    /**
     * Decode token IDs to text
     */
    fun decode(tokenIds: List<Int>): String {
        return tokenIds
            .mapNotNull { reverseVocabulary[it] }
            .filter { it != BOS_TOKEN && it != EOS_TOKEN && it != PAD_TOKEN }
            .joinToString(" ")
    }
}
