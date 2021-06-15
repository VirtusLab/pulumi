package io.pulumi.core.internal;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import io.grpc.Internal;
import io.pulumi.core.Input;
import io.pulumi.core.Output;
import io.pulumi.core.Tuples;
import io.pulumi.deployment.Deployment;
import io.pulumi.resources.Resource;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Immutable internal type
 */
@ParametersAreNonnullByDefault
@Internal
public class OutputData<T> {
    private final ImmutableSet<Resource> resources; // TODO: is it ok to use guava here?
    @Nullable
    private final T value;
    private final boolean known;
    private final boolean secret;

    private OutputData(ImmutableSet<Resource> resources, @Nullable T value, boolean isKnown, boolean isSecret) {
        this.resources = Objects.requireNonNull(resources);
        this.value = value;
        this.known = isKnown; // TODO: should we check if 'value != null && isKnown'?
        this.secret = isSecret;
    }

    public static <T> OutputData<T> create(ImmutableSet<Resource> resources, @Nullable T value, boolean isKnown, boolean isSecret) {
        return new OutputData<>(resources, value, isKnown, isSecret);
    }

    public OutputData<T> withIsSecret(boolean isSecret) {
        return new OutputData<>(this.resources, this.value, this.known, isSecret);
    }

    public ImmutableSet<Resource> getResources() {
        return resources;
    }

    @Nullable
    public T getValue() {
        return value;
    }

    public boolean isKnown() {
        return known;
    }

    public boolean isSecret() {
        return secret;
    }

    public boolean isEmpty() {
        return value == null;
    }

    public static <T, U> CompletableFuture<OutputData<U>> apply(
            CompletableFuture<OutputData<T>> dataFuture,
            Function<T, CompletableFuture<OutputData<U>>> func
    ) {
        return dataFuture.thenApply((OutputData <T> data) -> {
            ImmutableSet<Resource> resources = data.getResources();

            // TODO: reference implementation had a special case for previews here
            //       but is it really needed or can it be done differently?
            // During previews only, perform the apply if the engine was able to
            // give us an actual value for this Output.
            if (!data.isKnown() && Deployment.getInstance().isDryRun()) {
                return CompletableFuture.completedFuture(
                        new OutputData<>(resources, (U) null, data.isKnown(), data.isSecret())
                );
            }

            CompletableFuture<OutputData<U>> inner = func.apply(data.value);
            Objects.requireNonNull(inner);
            return inner.thenApply(innerData -> new OutputData<>(
                    ImmutableSet.copyOf(Sets.union(resources, innerData.getResources())),
                    innerData.getValue(),
                    data.known && innerData.known,
                    data.secret || innerData.secret
            ));
        }).thenCompose(Function.identity()); // TODO: this looks ugly, what am I doing wrong?
    }

    @Internal
    public static <T> CompletableFuture<OutputData<List<T>>> internalAllInputs(List<Input<T>> inputs) {
        return internalAllHelperAsync(inputs
                .stream()
                .map(e -> e.toOutput().internalGetDataAsync())
                .collect(Collectors.toUnmodifiableList()));
    }

    @Internal
    public static <T> CompletableFuture<OutputData<List<T>>> internalAllOutputs(List<Output<T>> outputs) {
        return internalAllHelperAsync(outputs
                .stream()
                .map(Output::internalGetDataAsync)
                .collect(Collectors.toUnmodifiableList()));
    }

    @Internal
    private static <T> CompletableFuture<OutputData<List<T>>> internalAllHelperAsync(
            List<CompletableFuture<OutputData<T>>> outputs) {
        return CompletableFuture.allOf(outputs.toArray(new CompletableFuture<?>[outputs.size()]))
                .thenApply(ignore -> {
                    List<OutputData<T>> dataList = outputs
                            .stream()
                            .map(CompletableFuture::join)
                            .collect(Collectors.toList());

                    var resources = new HashSet<Resource>();
                    var values = new ArrayList<T>(outputs.size());
                    var isKnown = true;
                    var isSecret = false;

                    for (var outputData : dataList) {
                        if (outputData.value != null) {
                            values.add(outputData.value);
                        }
                        resources.addAll(outputData.resources);
                        isKnown = isKnown && outputData.known;
                        isSecret = isSecret || outputData.secret;
                    }

                    return new OutputData<>(
                            ImmutableSet.copyOf(resources),
                            ImmutableList.copyOf(values),
                            isKnown,
                            isSecret
                    );
                });
    }

    public static <T1, T2, T3, T4, T5, T6, T7, T8> CompletableFuture<OutputData<Tuples.Tuple8<T1, T2, T3, T4, T5, T6, T7, T8>>> tuple(
            Input<T1> input1, Input<T2> input2, Input<T3> input3, Input<T4> input4,
            Input<T5> input5, Input<T6> input6, Input<T7> input7, Input<T8> input8
    ) {
        return internalTupleHelperAsync(
                input1.toOutput().internalGetDataAsync(),
                input2.toOutput().internalGetDataAsync(),
                input3.toOutput().internalGetDataAsync(),
                input4.toOutput().internalGetDataAsync(),
                input5.toOutput().internalGetDataAsync(),
                input6.toOutput().internalGetDataAsync(),
                input7.toOutput().internalGetDataAsync(),
                input8.toOutput().internalGetDataAsync()
        );
    }

    public static <T1, T2, T3, T4, T5, T6, T7, T8> CompletableFuture<OutputData<Tuples.Tuple8<T1, T2, T3, T4, T5, T6, T7, T8>>> tuple(
            Output<T1> output1, Output<T2> output2, Output<T3> output3, Output<T4> output4,
            Output<T5> output5, Output<T6> output6, Output<T7> output7, Output<T8> output8
    ) {
        return internalTupleHelperAsync(
                output1.internalGetDataAsync(),
                output2.internalGetDataAsync(),
                output3.internalGetDataAsync(),
                output4.internalGetDataAsync(),
                output5.internalGetDataAsync(),
                output6.internalGetDataAsync(),
                output7.internalGetDataAsync(),
                output8.internalGetDataAsync()
        );
    }

    @Internal
    private static <T1, T2, T3, T4, T5, T6, T7, T8> CompletableFuture<OutputData<Tuples.Tuple8<T1, T2, T3, T4, T5, T6, T7, T8>>> internalTupleHelperAsync(
            CompletableFuture<OutputData<T1>> data1, CompletableFuture<OutputData<T2>> data2,
            CompletableFuture<OutputData<T3>> data3, CompletableFuture<OutputData<T4>> data4,
            CompletableFuture<OutputData<T5>> data5, CompletableFuture<OutputData<T6>> data6,
            CompletableFuture<OutputData<T7>> data7, CompletableFuture<OutputData<T8>> data8
    ) {
        return CompletableFuture.allOf(data1, data2, data3, data4, data5, data6, data7, data8)
                .thenApply(ignore -> {
                    final var resources = new HashSet<Resource>();
                    final var isKnown = MutableHolder.of(true);
                    final var isSecret = MutableHolder.of(false);

                    // TODO: this is an ugly hack, how to make it more typed?
                    Function<OutputData<?>, ?> accumulate = (OutputData<?> data) -> {
                        resources.addAll(data.resources);
                        isKnown.value = isKnown.value.booleanValue() && data.known;
                        isSecret.value = isSecret.value.booleanValue() || data.secret;
                        return data.value;
                    };

                    final var value1 = (T1) accumulate.apply(data1.join());
                    final var value2 = (T2) accumulate.apply(data2.join());
                    final var value3 = (T3) accumulate.apply(data3.join());
                    final var value4 = (T4) accumulate.apply(data4.join());
                    final var value5 = (T5) accumulate.apply(data5.join());
                    final var value6 = (T6) accumulate.apply(data6.join());
                    final var value7 = (T7) accumulate.apply(data7.join());
                    final var value8 = (T8) accumulate.apply(data8.join());

                    return new OutputData<>(
                            ImmutableSet.copyOf(resources),
                            Tuples.of(value1, value2, value3, value4, value5, value6, value7, value8),
                            isKnown.value,
                            isSecret.value
                    );
                });
    }

    private static class MutableHolder<T> {
        @Nullable
        public T value;

        public MutableHolder(@Nullable T value) {
            this.value = value;
        }

        public static <T> MutableHolder<T> of(@Nullable T value) {
            return new MutableHolder<>(value);
        }

        public static <T> MutableHolder<T> empty() {
            return new MutableHolder<>(null);
        }
    }
}

