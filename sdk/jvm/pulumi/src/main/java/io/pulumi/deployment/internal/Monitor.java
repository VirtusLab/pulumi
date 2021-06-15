package io.pulumi.deployment.internal;

import com.google.protobuf.Empty;
import pulumirpc.Provider.InvokeRequest;
import pulumirpc.Provider.InvokeResponse;
import pulumirpc.Resource;
import pulumirpc.Resource.*;

import java.util.concurrent.CompletableFuture;

public interface Monitor {
    CompletableFuture<SupportsFeatureResponse> supportsFeatureAsync(SupportsFeatureRequest request);

    CompletableFuture<InvokeResponse> invokeAsync(InvokeRequest request);

    CompletableFuture<ReadResourceResponse> readResourceAsync(Resource resource, ReadResourceRequest request);

    CompletableFuture<RegisterResourceResponse> registerResourceAsync(Resource resource, RegisterResourceRequest request);

    CompletableFuture<Empty> registerResourceOutputsAsync(RegisterResourceOutputsRequest request);
}
