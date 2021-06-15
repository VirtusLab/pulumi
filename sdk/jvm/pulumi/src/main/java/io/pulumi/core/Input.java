package io.pulumi.core;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Objects;

@ParametersAreNonnullByDefault
public class Input<T> {
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
