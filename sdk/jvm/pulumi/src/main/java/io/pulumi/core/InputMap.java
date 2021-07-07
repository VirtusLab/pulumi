package io.pulumi.core;

import io.pulumi.core.internal.InputOutputData;
import io.pulumi.core.internal.TypedInputOutput;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class InputMap<V> extends InputImpl<Map<String, V>, Input<Map<String, V>>> implements Input<Map<String, V>> {

    protected InputMap() {
        this(Map.of());
    }

    protected InputMap(Map<String, V> values) {
        super(values, false);
    }

    protected InputMap(Map<String, V> values, boolean secret) {
        super(values, secret);
    }

    protected InputMap(Input<Map<String, V>> inputs) {
        super(TypedInputOutput.cast(inputs).internalGetDataAsync());
    }

    protected InputMap(CompletableFuture<InputOutputData<Map<String, V>>> values) {
        super(values);
    }

    protected InputMap<V> newInstance(CompletableFuture<InputOutputData<Map<String, V>>> dataFuture) {
        return new InputMap<>(dataFuture);
    }

    @Override
    public <U> Input<U> applyInput(Function<Map<String, V>, Input<U>> func) {
        return new InputDefault<>(InputOutputData.apply(dataFuture, func.andThen(
                o -> TypedInputOutput.cast(o).internalGetDataAsync())
        ));
    }

    @Override
    public InputMap<V> copy() {
        return new InputMap<>(this.dataFuture.copy());
    }

 

    // Static section -----
    public static <V> InputMap<V> of(Map<String, V> values) {
        return new InputMap<>(values);
    }

    public static <T> InputMap<T> of(Input<Map<String, T>> values) {
        return new InputMap<>(values);
    }

    public static <V> InputMap<V> ofSecret(Map<String, V> value) {
        return new InputMap<>(value, true);
    }

    public static <K, V> InputMap<V> empty() {
        return new InputMap<>();
    }
}