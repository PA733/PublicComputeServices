package com.kieronquinn.app.pcs.grpc

import android.util.Log
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptors
import io.grpc.ClientInterceptor
import io.grpc.Context
import io.grpc.Contexts
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerMethodDefinition
import io.grpc.ServerInterceptor
import io.grpc.ServerServiceDefinition
import io.grpc.ServiceDescriptor
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.ClientCalls
import io.grpc.stub.MetadataUtils
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 *  Transparent byte level proxy for the On Device Safety (ODS) calls this module does not
 *  implement itself.
 *
 *  AICore only accepts a model file group once the "initial protections" for it resolve, and those
 *  protections come from ODS. The manifest endpoint is answered locally from the configured
 *  repository, but the rest of the service (`Download`, `GetVmDescriptor`, `DeleteVm`, and the
 *  HTTP download endpoint used to fetch the model bytes) has no local replacement, so AICore gets
 *  `UNIMPLEMENTED` and every model download aborts with `File key <hash> not found`.
 *
 *  Forwarding those calls to the real host at the byte level keeps working whatever the payload
 *  schema is, and lets ODS do the parts only it can do (content binding, protections, the
 *  attestation based HTTP proxy) while the manifest itself is still served from the repository.
 */
object OdsProxy {

    private const val TAG = "AstreaService"
    private const val HOST = "ondevicesafety-pa.googleapis.com"
    private const val PORT = 443
    /**
     *  The API key the app ships for this host, taken from the ODS client options.
     */
    private const val API_KEY = "AIzaSyBVISctL4wnC5nctQ1nGYDRD6zybQjKCL8"

    const val SERVICE_PD = "google.internal.abuse.ondevicesafety.v2.ProtectedDownloadService"
    const val SERVICE_PD_V2 = "com.google.android.apps.miphone.astrea.pd.api.ProtectedDownloadService"
    const val SERVICE_HTTP = "com.google.android.apps.miphone.astrea.http.api.HttpService"

    private val PROXIED_UNARY = listOf("Download", "GetVmDescriptor", "DeleteVm")
    private val PROXIED_HTTP = listOf("Download")

    private val BYTE_MARSHALLER = object: MethodDescriptor.Marshaller<ByteArray> {
        override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)
        override fun parse(stream: InputStream): ByteArray = stream.readBytes()
    }

    private val channel: ManagedChannel by lazy {
        OkHttpChannelBuilder.forAddress(HOST, PORT).build()
    }

    private val apiKeyMetadata = Metadata().apply {
        put(Metadata.Key.of("x-goog-api-key", Metadata.ASCII_STRING_MARSHALLER), API_KEY)
    }

    /**
     *  Headers the caller sent, minus the ones gRPC sets itself, so caller authentication and
     *  attestation metadata reach the real host unchanged.
     */
    private val HEADERS_CONTEXT = Context.key<Metadata>("pcsOdsProxyHeaders")

    private val IGNORED_HEADERS = setOf(
        "content-type", "te", "user-agent", "grpc-accept-encoding", "grpc-encoding",
        "grpc-timeout", "grpc-status", "grpc-message", "grpc-status-details-bin"
    )

    /**
     *  Re-attaches the caller's headers to the outgoing call, dropping the transport specific
     *  ones so the upstream call is unambiguous.
     */
    private val forwardedHeadersInterceptor = object: ClientInterceptor {
        override fun <ReqT, RespT> interceptCall(
            method: MethodDescriptor<ReqT, RespT>,
            callOptions: CallOptions,
            next: Channel
        ): ClientCall<ReqT, RespT> {
            val headers = HEADERS_CONTEXT.get() ?: return next.newCall(method, callOptions)
            val forwarded = Metadata()
            headers.keys().forEach { key ->
                if (key.lowercase() in IGNORED_HEADERS) return@forEach
                runCatching {
                    val metadataKey = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)
                    headers.getAll(metadataKey)?.forEach { forwarded.put(metadataKey, it) }
                }
            }
            return ClientInterceptors.intercept(
                next,
                MetadataUtils.newAttachHeadersInterceptor(forwarded)
            ).newCall(method, callOptions)
        }
    }

    private val proxiedChannel by lazy {
        ClientInterceptors.intercept(
            channel,
            MetadataUtils.newAttachHeadersInterceptor(apiKeyMetadata),
            forwardedHeadersInterceptor
        )
    }

    val capturingInterceptor: ServerInterceptor = object: ServerInterceptor {
        override fun <ReqT, RespT> interceptCall(
            call: ServerCall<ReqT, RespT>,
            headers: Metadata,
            next: ServerCallHandler<ReqT, RespT>
        ): ServerCall.Listener<ReqT> {
            return Contexts.interceptCall(
                Context.current().withValue(HEADERS_CONTEXT, headers),
                call,
                headers,
                next
            )
        }
    }

    /**
     *  Adds the proxied methods to a service definition that already has a local implementation
     *  (the protected download service keeps serving `GetManifestConfig` from the repository).
     */
    @Suppress("UNCHECKED_CAST")
    fun extend(existing: ServerServiceDefinition): ServerServiceDefinition {
        val serviceName = existing.serviceDescriptor.name
        val proxied = proxiedMethods(serviceName)
        val descriptorBuilder = ServiceDescriptor.newBuilder(serviceName)
        existing.serviceDescriptor.methods.forEach { descriptorBuilder.addMethod(it) }
        proxied.forEach { descriptorBuilder.addMethod(it.first) }
        val builder = ServerServiceDefinition.builder(descriptorBuilder.build())
        existing.methods.forEach { method ->
            builder.addMethod(method as ServerMethodDefinition<Any, Any>)
        }
        proxied.forEach { (descriptor, streaming, methodName) ->
            addProxiedMethod(builder, descriptor, streaming, serviceName, methodName)
        }
        Log.d(TAG, "Proxying unimplemented $serviceName calls to $HOST")
        return builder.build()
    }

    /**
     *  Service definitions for the ODS endpoints this module does not implement at all.
     */
    fun definitions(): List<ServerServiceDefinition> = listOf(
        definition(SERVICE_PD_V2),
        definition(SERVICE_HTTP)
    ).also {
        Log.d(TAG, "Proxying unimplemented ODS services to $HOST")
    }

    private fun definition(serviceName: String): ServerServiceDefinition {
        val proxied = proxiedMethods(serviceName)
        val descriptorBuilder = ServiceDescriptor.newBuilder(serviceName)
        proxied.forEach { descriptorBuilder.addMethod(it.first) }
        val builder = ServerServiceDefinition.builder(descriptorBuilder.build())
        proxied.forEach { (descriptor, streaming, methodName) ->
            addProxiedMethod(builder, descriptor, streaming, serviceName, methodName)
        }
        return builder.build()
    }

    private fun proxiedMethods(
        serviceName: String
    ): List<Triple<MethodDescriptor<ByteArray, ByteArray>, Boolean, String>> {
        val methods = if (serviceName == SERVICE_HTTP) PROXIED_HTTP else PROXIED_UNARY
        val streaming = serviceName == SERVICE_HTTP
        return methods.map { methodName ->
            val type = if (streaming) {
                MethodDescriptor.MethodType.SERVER_STREAMING
            } else {
                MethodDescriptor.MethodType.UNARY
            }
            Triple(methodDescriptor(serviceName, methodName, type), streaming, methodName)
        }
    }

    private fun addProxiedMethod(
        builder: ServerServiceDefinition.Builder,
        descriptor: MethodDescriptor<ByteArray, ByteArray>,
        streaming: Boolean,
        serviceName: String,
        methodName: String
    ) {
        if (streaming) {
            builder.addMethod(
                descriptor,
                ServerCalls.asyncServerStreamingCall(streamingHandler(serviceName, methodName))
            )
        } else {
            builder.addMethod(
                descriptor,
                ServerCalls.asyncUnaryCall(unaryHandler(serviceName, methodName))
            )
        }
    }

    private fun methodDescriptor(
        serviceName: String,
        methodName: String,
        type: MethodDescriptor.MethodType
    ): MethodDescriptor<ByteArray, ByteArray> =
        MethodDescriptor.newBuilder<ByteArray, ByteArray>()
            .setType(type)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(serviceName, methodName))
            .setRequestMarshaller(BYTE_MARSHALLER)
            .setResponseMarshaller(BYTE_MARSHALLER)
            .build()

    private fun unaryHandler(
        serviceName: String,
        methodName: String
    ): ServerCalls.UnaryMethod<ByteArray, ByteArray> =
        object: ServerCalls.UnaryMethod<ByteArray, ByteArray> {
            override fun invoke(request: ByteArray, responseObserver: StreamObserver<ByteArray>) {
                val name = "$serviceName/$methodName"
                Log.d(TAG, "Proxying $name (${request.size} bytes)")
                val call = newCall(serviceName, methodName, MethodDescriptor.MethodType.UNARY)
                ClientCalls.asyncUnaryCall(call, request, object: StreamObserver<ByteArray> {
                    override fun onNext(value: ByteArray) {
                        Log.d(TAG, "Proxied $name returned ${value.size} bytes")
                        responseObserver.onNext(value)
                    }

                    override fun onError(t: Throwable) {
                        Log.e(TAG, "Proxied $name failed: $t")
                        responseObserver.onError(t)
                    }

                    override fun onCompleted() {
                        responseObserver.onCompleted()
                    }
                })
            }
        }

    private fun streamingHandler(
        serviceName: String,
        methodName: String
    ): ServerCalls.ServerStreamingMethod<ByteArray, ByteArray> =
        object: ServerCalls.ServerStreamingMethod<ByteArray, ByteArray> {
            override fun invoke(request: ByteArray, responseObserver: StreamObserver<ByteArray>) {
                val name = "$serviceName/$methodName"
                Log.d(TAG, "Proxying stream $name (${request.size} bytes)")
                val call = newCall(
                    serviceName, methodName, MethodDescriptor.MethodType.SERVER_STREAMING
                )
                ClientCalls.asyncServerStreamingCall(
                    call,
                    request,
                    object: StreamObserver<ByteArray> {
                        override fun onNext(value: ByteArray) {
                            responseObserver.onNext(value)
                        }

                        override fun onError(t: Throwable) {
                            Log.e(TAG, "Proxied stream $name failed: $t")
                            responseObserver.onError(t)
                        }

                        override fun onCompleted() {
                            Log.d(TAG, "Proxied stream $name completed")
                            responseObserver.onCompleted()
                        }
                    }
                )
            }
        }

    private fun newCall(
        serviceName: String,
        methodName: String,
        type: MethodDescriptor.MethodType
    ): ClientCall<ByteArray, ByteArray> {
        return proxiedChannel.newCall(
            methodDescriptor(serviceName, methodName, type),
            CallOptions.DEFAULT
        )
    }
}
