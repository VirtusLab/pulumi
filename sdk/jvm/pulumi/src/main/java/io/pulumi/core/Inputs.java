package io.pulumi.core;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * Useful static utility methods for both creating and working with @see {@link Input}s.
 */
@ParametersAreNonnullByDefault
public class Inputs {
    private Inputs() {
        throw new UnsupportedOperationException();
    }

    public static <T> Input<T> create(T value) {
        return new Input<>(Outputs.create(value));
    }

    public static <T> Input<T> create(Output<T> value) {
        return new Input<>(value);
    }

    /* package */
    static <T> Input<T> internalCreateEmpty() {
        return create(Outputs.internalCreateEmpty());
    }

    public static <T> Input<T> copy(Input<T> original) {
        return original.copy();
    }
}
