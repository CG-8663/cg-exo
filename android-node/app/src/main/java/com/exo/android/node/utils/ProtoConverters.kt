package com.exo.android.node.utils

import com.exo.android.node.data.DeviceCapabilities
import com.exo.android.node.data.DeviceFlops
import com.exo.android.node.data.Shard
import com.exo.android.node.inference.InferenceState
import com.google.protobuf.ByteString
import node_service.NodeServiceProto
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utilities for converting between Kotlin data classes and Protocol Buffer messages
 */
object ProtoConverters {

    // ========== Shard Conversions ==========

    fun Shard.toProto(): NodeServiceProto.Shard {
        return NodeServiceProto.Shard.newBuilder()
            .setModelId(modelId)
            .setStartLayer(startLayer)
            .setEndLayer(endLayer)
            .setNLayers(nLayers)
            .build()
    }

    fun NodeServiceProto.Shard.toKotlin(): Shard {
        return Shard(
            modelId = modelId,
            startLayer = startLayer,
            endLayer = endLayer,
            nLayers = nLayers
        )
    }

    // ========== Tensor Conversions ==========

    /**
     * Convert FloatArray to Proto Tensor
     */
    fun FloatArray.toProtoTensor(shape: IntArray = intArrayOf(this.size)): NodeServiceProto.Tensor {
        val buffer = ByteBuffer.allocate(this.size * 4).order(ByteOrder.nativeOrder())
        this.forEach { buffer.putFloat(it) }

        return NodeServiceProto.Tensor.newBuilder()
            .setTensorData(ByteString.copyFrom(buffer.array()))
            .addAllShape(shape.toList())
            .setDtype("float32")
            .build()
    }

    /**
     * Convert Proto Tensor to FloatArray
     */
    fun NodeServiceProto.Tensor.toFloatArray(): FloatArray {
        val buffer = ByteBuffer.wrap(tensorData.toByteArray()).order(ByteOrder.nativeOrder())
        val size = shapeList.fold(1) { acc, dim -> acc * dim }
        return FloatArray(size) { buffer.float }
    }

    /**
     * Convert IntArray to Proto Tensor
     */
    fun IntArray.toProtoTensor(shape: IntArray = intArrayOf(this.size)): NodeServiceProto.Tensor {
        val buffer = ByteBuffer.allocate(this.size * 4).order(ByteOrder.nativeOrder())
        this.forEach { buffer.putInt(it) }

        return NodeServiceProto.Tensor.newBuilder()
            .setTensorData(ByteString.copyFrom(buffer.array()))
            .addAllShape(shape.toList())
            .setDtype("int32")
            .build()
    }

    /**
     * Convert Proto Tensor to IntArray
     */
    fun NodeServiceProto.Tensor.toIntArray(): IntArray {
        val buffer = ByteBuffer.wrap(tensorData.toByteArray()).order(ByteOrder.nativeOrder())
        val size = shapeList.fold(1) { acc, dim -> acc * dim }
        return IntArray(size) { buffer.int }
    }

    // ========== DeviceCapabilities Conversions ==========

    fun DeviceFlops.toProto(): NodeServiceProto.DeviceFlops {
        return NodeServiceProto.DeviceFlops.newBuilder()
            .setFp32(fp32.toDouble())
            .setFp16(fp16.toDouble())
            .setInt8(int8.toDouble())
            .build()
    }

    fun NodeServiceProto.DeviceFlops.toKotlin(): DeviceFlops {
        return DeviceFlops(
            fp32 = fp32.toFloat(),
            fp16 = fp16.toFloat(),
            int8 = int8.toFloat()
        )
    }

    fun DeviceCapabilities.toProto(): NodeServiceProto.DeviceCapabilities {
        return NodeServiceProto.DeviceCapabilities.newBuilder()
            .setModel(model)
            .setChip(chip)
            .setMemory(memory)
            .setFlops(flops.toProto())
            .build()
    }

    fun NodeServiceProto.DeviceCapabilities.toKotlin(): DeviceCapabilities {
        return DeviceCapabilities(
            model = model,
            chip = chip,
            memory = memory,
            flops = flops.toKotlin()
        )
    }

    // ========== InferenceState Conversions ==========

    fun InferenceState?.toProto(): NodeServiceProto.InferenceState {
        if (this == null) {
            return NodeServiceProto.InferenceState.newBuilder().build()
        }

        val builder = NodeServiceProto.InferenceState.newBuilder()

        // For MVP, we'll skip complex state serialization
        // In production, would serialize KV cache here

        return builder.build()
    }

    fun NodeServiceProto.InferenceState.toKotlin(): InferenceState? {
        if (tensorDataMap.isEmpty() && tensorListDataMap.isEmpty() && otherDataJson.isEmpty()) {
            return null
        }

        // For MVP, return null
        // In production, would deserialize KV cache
        return null
    }

    // ========== Topology Conversions ==========

    data class PeerConnection(
        val toId: String,
        val description: String?
    )

    data class Topology(
        val nodes: Map<String, DeviceCapabilities>,
        val peerGraph: Map<String, List<PeerConnection>>
    )

    fun Topology.toProto(): NodeServiceProto.Topology {
        val builder = NodeServiceProto.Topology.newBuilder()

        // Add nodes
        nodes.forEach { (nodeId, caps) ->
            builder.putNodes(nodeId, caps.toProto())
        }

        // Add peer graph
        peerGraph.forEach { (nodeId, connections) ->
            val peerConns = NodeServiceProto.PeerConnections.newBuilder()
            connections.forEach { conn ->
                val peerConn = NodeServiceProto.PeerConnection.newBuilder()
                    .setToId(conn.toId)
                if (conn.description != null) {
                    peerConn.setDescription(conn.description)
                }
                peerConns.addConnections(peerConn)
            }
            builder.putPeerGraph(nodeId, peerConns.build())
        }

        return builder.build()
    }

    fun NodeServiceProto.Topology.toKotlin(): Topology {
        val nodes = nodesMap.mapValues { it.value.toKotlin() }

        val peerGraph = peerGraphMap.mapValues { (_, peerConns) ->
            peerConns.connectionsList.map { conn ->
                PeerConnection(
                    toId = conn.toId,
                    description = if (conn.hasDescription()) conn.description else null
                )
            }
        }

        return Topology(
            nodes = nodes,
            peerGraph = peerGraph
        )
    }
}
