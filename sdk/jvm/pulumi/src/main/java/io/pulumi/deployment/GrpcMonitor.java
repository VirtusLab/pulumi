package io.pulumi.deployment;

import com.google.protobuf.Empty;
import io.grpc.ManagedChannelBuilder;

import static net.javacrumbs.futureconverter.java8guava.FutureConverter.toCompletableFuture;
import static pulumirpc.ResourceMonitorGrpc.newFutureStub;

import pulumirpc.Provider;
import pulumirpc.Resource;
import pulumirpc.ResourceMonitorGrpc;

import java.util.concurrent.CompletableFuture;

public class GrpcMonitor implements Monitor {
    private final ResourceMonitorGrpc.ResourceMonitorFutureStub monitor;

    public GrpcMonitor(String monitor) {
        // maxRpcMessageSize raises the gRPC Max Message size from `4194304` (4mb) to `419430400` (400mb)
        var maxRpcMessageSizeInBytes = 400 * 1024 * 1024;
        this.monitor = newFutureStub(
                ManagedChannelBuilder
                        .forTarget(monitor)
                        .maxInboundMessageSize(maxRpcMessageSizeInBytes)
                        .build()
        );
    }

    @Override
    public CompletableFuture<Resource.SupportsFeatureResponse> supportsFeatureAsync(Resource.SupportsFeatureRequest request) {
        return toCompletableFuture(this.monitor.supportsFeature(request));
    }

    @Override
    public CompletableFuture<Provider.InvokeResponse> invokeAsync(Provider.InvokeRequest request) {
        return toCompletableFuture(this.monitor.invoke(request));
    }

    @Override
    public CompletableFuture<Resource.ReadResourceResponse> readResourceAsync(Resource resource, Resource.ReadResourceRequest request) {
        return toCompletableFuture(this.monitor.readResource(request));
    }

    @Override
    public CompletableFuture<Resource.RegisterResourceResponse> registerResourceAsync(Resource resource, Resource.RegisterResourceRequest request) {
        return toCompletableFuture(this.monitor.registerResource(request));
    }

    @Override
    public CompletableFuture<Empty> registerResourceOutputsAsync(Resource.RegisterResourceOutputsRequest request) {
        return toCompletableFuture(this.monitor.registerResourceOutputs(request));
    }
}
