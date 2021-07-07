package io.pulumi.deployment.internal;

import com.google.protobuf.Empty;

import java.util.concurrent.CompletableFuture;

import static pulumirpc.EngineOuterClass.*;

public interface Engine {
    CompletableFuture<Void> logAsync(LogRequest request);

    CompletableFuture<SetRootResourceResponse> setRootResourceAsync(SetRootResourceRequest request);

    CompletableFuture<GetRootResourceResponse> getRootResourceAsync(GetRootResourceRequest request);
}
