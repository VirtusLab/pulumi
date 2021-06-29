package io.pulumi.core;

import java.util.Objects;

public class Input<T> {
    protected Output<T> outputValue;

    protected Input(Output<T> outputValue) {
        Objects.requireNonNull(outputValue);
        this.outputValue = outputValue;
    }

    public static <T> Input<T> create(T value) {
        return new Input<>(Output.create(value));
    }

    public static <T> Input<T> create(Output<T> value) {
        return new Input<>(value);
    }

    // TODO
}
