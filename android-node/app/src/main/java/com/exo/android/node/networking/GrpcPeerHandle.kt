package com.exo.android.node.networking

import com.exo.android.node.data.Shard
import com.exo.android.node.inference.InferenceState
import com.exo.android.node.utils.ProtoConverters
import com.exo.android.node.utils.ProtoConverters.toFloatArray
import com.exo.android.node.utils.ProtoConverters.toKotlin
import com.exo.android.node.utils.ProtoConverters.toProto
import com.exo.android.node.utils.ProtoConverters.toProtoTensor
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import node_service.NodeServiceGrpc
import node_service.NodeServiceProto
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Handle for communicating with a peer node via gRPC
 * Mirrors Python GrpcPeerHandle from exo/networking/grpc/grpc_peer_handle.py
 */
class GrpcPeerHandle(
    override val peerId: String,
    private val address: String,
    private val port: Int
) : PeerHandle {

    private val channel: ManagedChannel = ManagedChannelBuilder
        .forAddress(address, port)
        .usePlaintext() // For MVP, no TLS
        .maxInboundMessageSize(256 * 1024 * 1024) // 256MB
        .maxInboundMetadataSize(32 * 1024 * 1024)  // 32MB
        .keepAliveTime(10, TimeUnit.SECONDS)
        .keepAliveTimeout(5, TimeUnit.SECONDS)
        .build()

    private val blockingStub = NodeServiceGrpc.newBlockingStub(channel)

    override suspend fun sendPrompt(
        shard: Shard,
        prompt: String,
        requestId: String,
        inferenceState: InferenceState?
    ): FloatArray = withContext(Dispatchers.IO) {
        Timber.d("Sending prompt to peer $peerId: $requestId")

        try {
            val request = NodeServiceProto.PromptRequest.newBuilder()
                .setShard(shard.toProto())
                .setPrompt(prompt)
                .setRequestId(requestId)
                .setInferenceState(inferenceState.toProto())
                .build()

            val response = blockingStub.sendPrompt(request)
            val result = response.toFloatArray()

            Timber.d("Received prompt response from $peerId: ${result.size} floats")
            result
        } catch (e: Exception) {
            Timber.e(e, "Failed to send prompt to peer $peerId")
            throw PeerCommunicationException("Failed to send prompt to $peerId", e)
        }
    }

    override suspend fun sendTensor(
        shard: Shard,
        tensor: FloatArray,
        requestId: String,
        inferenceState: InferenceState?
    ): FloatArray = withContext(Dispatchers.IO) {
        Timber.d("Sending tensor to peer $peerId: $requestId (${tensor.size} floats)")

        try {
            val request = NodeServiceProto.TensorRequest.newBuilder()
                .setShard(shard.toProto())
                .setTensor(tensor.toProtoTensor())
                .setRequestId(requestId)
                .setInferenceState(inferenceState.toProto())
                .build()

            val response = blockingStub.sendTensor(request)
            val result = response.toFloatArray()

            Timber.d("Received tensor response from $peerId: ${result.size} floats")
            result
        } catch (e: Exception) {
            Timber.e(e, "Failed to send tensor to peer $peerId")
            throw PeerCommunicationException("Failed to send tensor to $peerId", e)
        }
    }

    override suspend fun collectTopology(
        visited: Set<String>,
        maxDepth: Int
    ): ProtoConverters.Topology = withContext(Dispatchers.IO) {
        Timber.d("Collecting topology from peer $peerId")

        try {
            val request = NodeServiceProto.CollectTopologyRequest.newBuilder()
                .addAllVisited(visited)
                .setMaxDepth(maxDepth)
                .build()

            val response = blockingStub.collectTopology(request)
            val topology = response.toKotlin()

            Timber.d("Received topology from $peerId: ${topology.nodes.size} nodes")
            topology
        } catch (e: Exception) {
            Timber.e(e, "Failed to collect topology from peer $peerId")
            throw PeerCommunicationException("Failed to collect topology from $peerId", e)
        }
    }

    override suspend fun sendResult(
        requestId: String,
        result: List<Int>,
        isFinished: Boolean
    ) = withContext(Dispatchers.IO) {
        Timber.d("Sending result to peer $peerId: $requestId")

        try {
            val request = NodeServiceProto.SendResultRequest.newBuilder()
                .setRequestId(requestId)
                .addAllResult(result)
                .setIsFinished(isFinished)
                .build()

            blockingStub.sendResult(request)
            Timber.d("Result sent to $peerId successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to send result to peer $peerId")
            throw PeerCommunicationException("Failed to send result to $peerId", e)
        }
    }

    override suspend fun healthCheck(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = NodeServiceProto.HealthCheckRequest.newBuilder().build()
            val response = blockingStub.healthCheck(request)
            response.isHealthy
        } catch (e: Exception) {
            Timber.w(e, "Health check failed for peer $peerId")
            false
        }
    }

    override fun close() {
        Timber.d("Closing connection to peer $peerId")
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }

    override fun toString(): String {
        return "GrpcPeerHandle(peerId='$peerId', address='$address:$port')"
    }
}

/**
 * Interface for peer communication
 */
interface PeerHandle {
    val peerId: String

    suspend fun sendPrompt(
        shard: Shard,
        prompt: String,
        requestId: String,
        inferenceState: InferenceState? = null
    ): FloatArray

    suspend fun sendTensor(
        shard: Shard,
        tensor: FloatArray,
        requestId: String,
        inferenceState: InferenceState? = null
    ): FloatArray

    suspend fun collectTopology(
        visited: Set<String>,
        maxDepth: Int
    ): ProtoConverters.Topology

    suspend fun sendResult(
        requestId: String,
        result: List<Int>,
        isFinished: Boolean
    )

    suspend fun healthCheck(): Boolean

    fun close()
}

/**
 * Exception thrown when peer communication fails
 */
class PeerCommunicationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
