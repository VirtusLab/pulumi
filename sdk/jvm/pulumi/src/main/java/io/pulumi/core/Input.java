package io.pulumi.core;

import io.grpc.Internal;
import io.pulumi.core.internal.Copyable;
import io.pulumi.core.internal.OutputData;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@ParametersAreNonnullByDefault
public class Input<T> implements Copyable<Input<T>> {
    protected final Output<T> outputValue;

    /* package */ Input(Output<T> outputValue) {
        Objects.requireNonNull(outputValue);
        this.outputValue = outputValue;
    }

    public Input<T> copy() {
        return new Input<>(this.outputValue.copy());
    }

    public Output<T> toOutput() {
        return outputValue.copy();
    }

    @Internal
    public CompletableFuture<T> internalGetValueAsync() {
        return this.outputValue.internalGetValueAsync();
    }

    // TODO

    /* package */ static Input<Object> internalToObjectInput(@Nullable Object obj) {
        if (obj == null) {
            return Inputs.create(Outputs.internalCreateEmpty());
        } else if (obj instanceof Input) {
            //noinspection unchecked,rawtypes,rawtypes
            return Inputs.copy((Input) obj);
        } else if (obj instanceof Output) {
            return Inputs.create(obj);
        } else {
            throw new IllegalArgumentException("expected Input or Output as 'obj', got: " + obj.getClass().getName());
        }
    }
}
