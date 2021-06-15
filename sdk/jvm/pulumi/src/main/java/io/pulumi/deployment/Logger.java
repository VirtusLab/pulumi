package io.pulumi.deployment;

import io.pulumi.resources.Resource;

import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

public interface Logger {
    boolean hasLoggedErrors();

    CompletableFuture<Void> debugAsync(String message, @Nullable Resource resource, @Nullable Integer streamId, @Nullable Boolean ephemeral);
    CompletableFuture<Void> infoAsync(String message, @Nullable Resource resource, @Nullable Integer streamId, @Nullable Boolean ephemeral);
    CompletableFuture<Void> warnAsync(String message, @Nullable Resource resource, @Nullable Integer streamId, @Nullable Boolean ephemeral);
    CompletableFuture<Void> errorAsync(String message, @Nullable Resource resource, @Nullable Integer streamId, @Nullable Boolean ephemeral);
}
