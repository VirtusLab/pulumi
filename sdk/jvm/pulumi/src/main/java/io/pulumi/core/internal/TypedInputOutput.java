package io.pulumi.core.internal;

import io.grpc.Internal;
import io.pulumi.core.InputOutput;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Internal interface to allow our code to operate on inputs/outputs in a typed manner.
 */
@ParametersAreNonnullByDefault
@Internal
public interface TypedInputOutput<T> {
    CompletableFuture<InputOutputData<T>> internalGetDataAsync();

    CompletableFuture<T> internalGetValueAsync();

    static <T, IO extends InputOutput<T, IO> & Copyable<IO>> TypedInputOutput<T> cast(
            InputOutput<T, IO> inputOutput) {
        Objects.requireNonNull(inputOutput);
        if (inputOutput instanceof TypedInputOutput) {
            return (TypedInputOutput<T>) inputOutput;
        } else {
            throw new IllegalArgumentException(
                    String.format(
                            "Expected a 'TypedInputOutput<T>' instance, got: %s",
                            inputOutput.getClass().getSimpleName())
            );
        }
    }
}
