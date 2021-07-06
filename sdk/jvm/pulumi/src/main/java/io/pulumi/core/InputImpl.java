package io.pulumi.core;

import io.grpc.Internal;
import io.pulumi.core.internal.Copyable;
import io.pulumi.core.internal.InputOutputData;
import io.pulumi.core.internal.InputOutputImpl;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.concurrent.CompletableFuture;

@Internal
@ParametersAreNonnullByDefault
abstract class InputImpl<T, IO extends InputOutput<T, IO> & Copyable<IO>> extends InputOutputImpl<T, IO> {
    @Internal
    static final Input<Void> ZeroIn = Input.empty();

    protected InputImpl(CompletableFuture<InputOutputData<T>> dataFuture) {
        super(dataFuture);
    }

    public Output<T> toOutput() {
        return new OutputDefault<>(dataFuture.copy());
    }
}
