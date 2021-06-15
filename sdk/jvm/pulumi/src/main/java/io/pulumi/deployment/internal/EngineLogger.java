package io.pulumi.deployment.internal;

import io.pulumi.resources.Resource;

import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

public interface EngineLogger {
    boolean hasLoggedErrors();

    CompletableFuture<Void> debugAsync(String message);
    CompletableFuture<Void> infoAsync(String message);
    CompletableFuture<Void> warnAsync(String message);
    CompletableFuture<Void> errorAsync(String message);

    CompletableFuture<Void> debugAsync(String message, @Nullable Resource resource);
    CompletableFuture<Void> infoAsync(String message, @Nullable Resource resource);
    CompletableFuture<Void> warnAsync(String message, @Nullable Resource resource);
    CompletableFuture<Void> errorAsync(String message, @Nullable Resource resource);

    CompletableFuture<Void> debugAsync(String message, @Nullable Resource resource, @Nullable Integer streamId, @Nullable Boolean ephemeral);
    CompletableFuture<Void> infoAsync(String message, @Nullable Resource resource, @Nullable Integer streamId, @Nullable Boolean ephemeral);
    CompletableFuture<Void> warnAsync(String message, @Nullable Resource resource, @Nullable Integer streamId, @Nullable Boolean ephemeral);
    CompletableFuture<Void> errorAsync(String message, @Nullable Resource resource, @Nullable Integer streamId, @Nullable Boolean ephemeral);
}
