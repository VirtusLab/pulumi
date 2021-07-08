package io.pulumi.core;

import io.grpc.Internal;
import io.pulumi.core.internal.InputOutputData;
import io.pulumi.core.internal.TypedInputOutput;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

@ParametersAreNonnullByDefault
@Internal
public final class InputDefault<T> extends InputImpl<T, Input<T>> implements Input<T> {
    @Internal
    static final Input<Void> ZeroIn = Input.empty();

    InputDefault(T value) {
        super(value);
    }

    InputDefault(T value, boolean isSecret) {
        super(value, isSecret);
    }

    InputDefault(CompletableFuture<T> value, boolean isSecret) {
        super(value, isSecret);
    }

    InputDefault(InputOutputData<T> dataFuture) {
        super(dataFuture);
    }

    InputDefault(CompletableFuture<InputOutputData<T>> dataFuture) {
        super(dataFuture);
    }

    @Override
    protected InputDefault<T> newInstance(CompletableFuture<InputOutputData<T>> dataFuture) {
        return new InputDefault<>(dataFuture);
    }

    @Override
    public <U> Input<U> applyInput(Function<T, Input<U>> func) {
        return new InputDefault<>(InputOutputData.apply(dataFuture, func.andThen(
                o -> TypedInputOutput.cast(o).internalGetDataAsync())));
    }

    // Static section -----

    @Internal
    static <T> Input<T> internalCreateUnknown(T value) { // TODO: try to remove
        return new InputDefault<>(CompletableFuture.completedFuture(InputOutputData.unknown(value)));
    }
}
