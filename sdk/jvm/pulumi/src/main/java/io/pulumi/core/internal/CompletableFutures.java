package io.pulumi.core.internal;

import io.grpc.Internal;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

@Internal
public class CompletableFutures {
    private CompletableFutures() {
        throw new UnsupportedOperationException();
    }

    public static <T> CompletableFuture<Collection<CompletableFuture<T>>> allOf(Collection<CompletableFuture<T>> futures) {
        return CompletableFuture
                .allOf(futures.toArray(new CompletableFuture[futures.size()]))
                .thenApply(unused -> futures);
    }
}
