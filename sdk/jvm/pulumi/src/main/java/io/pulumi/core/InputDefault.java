package io.pulumi.core;

import io.grpc.Internal;
import io.pulumi.core.internal.InputOutputData;
import io.pulumi.core.internal.TypedInputOutput;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

@Internal
public class InputDefault<T> extends InputImpl<T, Input<T>> implements Input<T> {
    protected InputDefault(CompletableFuture<InputOutputData<T>> dataFuture) {
        super(dataFuture);
    }

    @Override
    protected InputDefault<T> newInstance(CompletableFuture<InputOutputData<T>> dataFuture) {
        return new InputDefault<>(dataFuture);
    }

    @Override
    public <U> Input<U> applyInput(Function<T, Input<U>> func) {
        return new InputDefault<>(InputOutputData.apply(dataFuture, func.andThen(
                o -> ((TypedInputOutput<U>) o).internalGetDataAsync())));
    }

    // Static section -----

    @Internal
    static <T> Input<T> internalCreateUnknown(T value) {
        return new InputDefault<>(CompletableFuture.completedFuture(InputOutputData.unknown(value)));
    }
}
