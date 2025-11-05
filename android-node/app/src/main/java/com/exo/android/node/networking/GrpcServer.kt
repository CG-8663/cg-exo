package com.exo.android.node.networking

import com.exo.android.node.data.Shard
import com.exo.android.node.utils.ProtoConverters
import com.exo.android.node.utils.ProtoConverters.toFloatArray
import com.exo.android.node.utils.ProtoConverters.toKotlin
import com.exo.android.node.utils.ProtoConverters.toProto
import com.exo.android.node.utils.ProtoConverters.toProtoTensor
import io.grpc.Grpc
import io.grpc.InsecureServerCredentials
import io.grpc.Server
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import node_service.NodeServiceGrpc
import node_service.NodeServiceProto
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * gRPC server for receiving inference requests from peers
 * Implements NodeService from node_service.proto
 */
class GrpcServer(
    private val port: Int,
    private val nodeHandler: NodeRequestHandler
) {
    private var server: Server? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    /**
     * Start the gRPC server
     */
    fun start() {
        server = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
            .addService(NodeServiceImpl(nodeHandler, coroutineScope))
            .maxInboundMessageSize(256 * 1024 * 1024) // 256MB
            .maxInboundMetadataSize(32 * 1024 * 1024)  // 32MB
            .build()
            .start()

        Timber.i("gRPC server started on port $port")

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(Thread {
            Timber.i("Shutting down gRPC server on JVM shutdown")
            stop()
        })
    }

    /**
     * Stop the gRPC server
     */
    fun stop() {
        server?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS)
        Timber.i("gRPC server stopped")
    }

    /**
     * Block until server is terminated
     */
    fun blockUntilShutdown() {
        server?.awaitTermination()
    }

    /**
     * Implementation of NodeService gRPC service
     */
    private class NodeServiceImpl(
        private val handler: NodeRequestHandler,
        private val scope: CoroutineScope
    ) : NodeServiceGrpc.NodeServiceImplBase() {

        override fun sendPrompt(
            request: NodeServiceProto.PromptRequest,
            responseObserver: StreamObserver<NodeServiceProto.Tensor>
        ) {
            scope.launch {
                try {
                    Timber.d("Received SendPrompt request: ${request.requestId}")

                    val shard = request.shard.toKotlin()
                    val requestId = request.requestId.takeIf { it.isNotEmpty() }
                        ?: generateRequestId()
                    val inferenceState = request.inferenceState.toKotlin()

                    val result = handler.handlePromptRequest(
                        shard = shard,
                        prompt = request.prompt,
                        requestId = requestId,
                        inferenceState = inferenceState
                    )

                    val response = result.toProtoTensor()
                    responseObserver.onNext(response)
                    responseObserver.onCompleted()

                    Timber.d("SendPrompt completed: $requestId")
                } catch (e: Exception) {
                    Timber.e(e, "Error handling SendPrompt")
                    responseObserver.onError(e)
                }
            }
        }

        override fun sendTensor(
            request: NodeServiceProto.TensorRequest,
            responseObserver: StreamObserver<NodeServiceProto.Tensor>
        ) {
            scope.launch {
                try {
                    Timber.d("Received SendTensor request: ${request.requestId}")

                    val shard = request.shard.toKotlin()
                    val inputData = request.tensor.toFloatArray()
                    val requestId = request.requestId.takeIf { it.isNotEmpty() }
                        ?: generateRequestId()
                    val inferenceState = request.inferenceState.toKotlin()

                    val result = handler.handleTensorRequest(
                        shard = shard,
                        inputData = inputData,
                        requestId = requestId,
                        inferenceState = inferenceState
                    )

                    val response = result.toProtoTensor()
                    responseObserver.onNext(response)
                    responseObserver.onCompleted()

                    Timber.d("SendTensor completed: $requestId")
                } catch (e: Exception) {
                    Timber.e(e, "Error handling SendTensor")
                    responseObserver.onError(e)
                }
            }
        }

        override fun collectTopology(
            request: NodeServiceProto.CollectTopologyRequest,
            responseObserver: StreamObserver<NodeServiceProto.Topology>
        ) {
            scope.launch {
                try {
                    Timber.d("Received CollectTopology request")

                    val visited = request.visitedList.toSet()
                    val maxDepth = request.maxDepth

                    val topology = handler.handleTopologyRequest(visited, maxDepth)
                    val response = topology.toProto()

                    responseObserver.onNext(response)
                    responseObserver.onCompleted()

                    Timber.d("CollectTopology completed")
                } catch (e: Exception) {
                    Timber.e(e, "Error handling CollectTopology")
                    responseObserver.onError(e)
                }
            }
        }

        override fun sendResult(
            request: NodeServiceProto.SendResultRequest,
            responseObserver: StreamObserver<NodeServiceProto.Empty>
        ) {
            scope.launch {
                try {
                    Timber.d("Received SendResult request: ${request.requestId}")

                    handler.handleResultRequest(
                        requestId = request.requestId,
                        result = request.resultList,
                        isFinished = request.isFinished
                    )

                    responseObserver.onNext(NodeServiceProto.Empty.newBuilder().build())
                    responseObserver.onCompleted()

                    Timber.d("SendResult completed: ${request.requestId}")
                } catch (e: Exception) {
                    Timber.e(e, "Error handling SendResult")
                    responseObserver.onError(e)
                }
            }
        }

        override fun sendOpaqueStatus(
            request: NodeServiceProto.SendOpaqueStatusRequest,
            responseObserver: StreamObserver<NodeServiceProto.Empty>
        ) {
            scope.launch {
                try {
                    Timber.d("Received SendOpaqueStatus: ${request.requestId}")

                    handler.handleOpaqueStatus(
                        requestId = request.requestId,
                        status = request.status
                    )

                    responseObserver.onNext(NodeServiceProto.Empty.newBuilder().build())
                    responseObserver.onCompleted()
                } catch (e: Exception) {
                    Timber.e(e, "Error handling SendOpaqueStatus")
                    responseObserver.onError(e)
                }
            }
        }

        override fun healthCheck(
            request: NodeServiceProto.HealthCheckRequest,
            responseObserver: StreamObserver<NodeServiceProto.HealthCheckResponse>
        ) {
            try {
                val isHealthy = handler.isHealthy()
                val response = NodeServiceProto.HealthCheckResponse.newBuilder()
                    .setIsHealthy(isHealthy)
                    .build()

                responseObserver.onNext(response)
                responseObserver.onCompleted()

                Timber.d("HealthCheck completed: healthy=$isHealthy")
            } catch (e: Exception) {
                Timber.e(e, "Error handling HealthCheck")
                responseObserver.onError(e)
            }
        }

        private fun generateRequestId(): String {
            return "android_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
        }
    }
}

/**
 * Interface for handling node requests
 * Implemented by AndroidNode
 */
interface NodeRequestHandler {
    /**
     * Handle text prompt inference request
     */
    suspend fun handlePromptRequest(
        shard: Shard,
        prompt: String,
        requestId: String,
        inferenceState: com.exo.android.node.inference.InferenceState?
    ): FloatArray

    /**
     * Handle tensor inference request
     */
    suspend fun handleTensorRequest(
        shard: Shard,
        inputData: FloatArray,
        requestId: String,
        inferenceState: com.exo.android.node.inference.InferenceState?
    ): FloatArray

    /**
     * Handle topology collection request
     */
    suspend fun handleTopologyRequest(
        visited: Set<String>,
        maxDepth: Int
    ): ProtoConverters.Topology

    /**
     * Handle result notification
     */
    suspend fun handleResultRequest(
        requestId: String,
        result: List<Int>,
        isFinished: Boolean
    )

    /**
     * Handle opaque status message
     */
    suspend fun handleOpaqueStatus(
        requestId: String,
        status: String
    )

    /**
     * Check if node is healthy
     */
    fun isHealthy(): Boolean
}
