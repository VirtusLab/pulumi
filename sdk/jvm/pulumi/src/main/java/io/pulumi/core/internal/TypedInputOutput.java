package io.pulumi.core.internal;

import io.grpc.Internal;

import java.util.concurrent.CompletableFuture;

/**
 * Internal interface to allow our code to operate on inputs/outputs in a typed manner.
 */
@Internal
public interface TypedInputOutput<T> {
    CompletableFuture<InputOutputData<T>> internalGetDataAsync();
    CompletableFuture<T> internalGetValueAsync();
}
