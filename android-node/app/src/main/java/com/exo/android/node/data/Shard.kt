package com.exo.android.node.data

import kotlinx.serialization.Serializable

/**
 * Represents a partition of model layers assigned to a node.
 * Mirrors the Python Shard class from exo/inference/shard.py
 */
@Serializable
data class Shard(
    val modelId: String,
    val startLayer: Int,
    val endLayer: Int,
    val nLayers: Int
) {
    /**
     * Check if this shard contains the first layer of the model
     */
    fun isFirstLayer(): Boolean = startLayer == 0

    /**
     * Check if this shard contains the last layer of the model
     */
    fun isLastLayer(): Boolean = endLayer == nLayers - 1

    /**
     * Get the number of layers in this shard
     */
    fun getLayerCount(): Int = endLayer - startLayer + 1

    /**
     * Check if this shard overlaps with another shard
     */
    fun overlaps(other: Shard): Boolean {
        return modelId == other.modelId &&
                maxOf(startLayer, other.startLayer) <= minOf(endLayer, other.endLayer)
    }

    /**
     * Convert to map for serialization
     */
    fun toMap(): Map<String, Any> = mapOf(
        "model_id" to modelId,
        "start_layer" to startLayer,
        "end_layer" to endLayer,
        "n_layers" to nLayers
    )

    companion object {
        /**
         * Create Shard from map
         */
        fun fromMap(data: Map<String, Any>): Shard {
            return Shard(
                modelId = data["model_id"] as String,
                startLayer = (data["start_layer"] as Number).toInt(),
                endLayer = (data["end_layer"] as Number).toInt(),
                nLayers = (data["n_layers"] as Number).toInt()
            )
        }
    }
}
