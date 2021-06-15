package io.pulumi.deployment;

import com.google.protobuf.Empty;
import io.grpc.ManagedChannelBuilder;

import static net.javacrumbs.futureconverter.java8guava.FutureConverter.toCompletableFuture;
import static pulumirpc.EngineGrpc.newFutureStub;

import pulumirpc.EngineGrpc;
import pulumirpc.EngineOuterClass.*;

import java.util.concurrent.CompletableFuture;

public class GrpcEngine implements Engine {
    private final EngineGrpc.EngineFutureStub engine;

    public GrpcEngine(String engine) {
        // maxRpcMessageSize raises the gRPC Max Message size from `4194304` (4mb) to `419430400` (400mb)
        var maxRpcMessageSizeInBytes = 400 * 1024 * 1024;
        this.engine = newFutureStub(
                ManagedChannelBuilder
                        .forTarget(engine)
                        .maxInboundMessageSize(maxRpcMessageSizeInBytes)
                        .build()
        );
    }

    @Override
    public CompletableFuture<Empty> logAsync(LogRequest request) {
        return toCompletableFuture(this.engine.log(request));
    }

    @Override
    public CompletableFuture<SetRootResourceResponse> setRootResourceAsync(SetRootResourceRequest request) {
        return toCompletableFuture(this.engine.setRootResource(request));
    }

    @Override
    public CompletableFuture<GetRootResourceResponse> getRootResourceAsync(GetRootResourceRequest request) {
        return toCompletableFuture(this.engine.getRootResource(request));
    }
}
