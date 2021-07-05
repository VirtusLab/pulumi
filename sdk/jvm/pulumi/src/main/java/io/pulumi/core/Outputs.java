package io.pulumi.core;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import io.grpc.Internal;
import io.pulumi.core.Tuples.*;
import io.pulumi.core.internal.OutputData;
import io.pulumi.resources.Resource;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.pulumi.core.internal.OutputData.internalAllInputs;
import static io.pulumi.core.internal.OutputData.internalAllOutputs;

/**
 * Useful static utility methods for both creating and working with @see {@link Output}s.
 */
@ParametersAreNonnullByDefault
public class Outputs {
    private Outputs() {
        throw new UnsupportedOperationException();
    }

    public static <T> Output<T> create(T value) {
        Objects.requireNonNull(value);
        return create(CompletableFuture.completedFuture(value));
    }

    public static <T> Output<T> create(CompletableFuture<T> value) {
        return create(value, false);
    }

    public static <T> Output<T> createSecret(T value) {
        Objects.requireNonNull(value);
        return internalCreateSecret(CompletableFuture.completedFuture(value));
    }

    /**
     * Creates a shallow copy (the underlying CompletableFuture is copied) of a given @see {@link Output<T>}
     *
     * @param original the original @see {@link Output<T>} to be copied
     * @param <T>      the value type
     * @return a shallow copy of the @see {@link Output<T>}
     */
    public static <T> Output<T> copy(Output<T> original) {
        return original.copy();
    }

    /* package */
    static <T> Output<T> internalCreateSecret(CompletableFuture<T> value) {
        return create(value, true);
    }

    /**
     * Returns a new @see {@link Output<T>} which is a copy of the existing output but marked as
     * a non-secret. The original output is not modified in any way.
     */
    public static <T> Output<T> unsecret(Output<T> output) {
        Objects.requireNonNull(output);
        return output.internalWithIsSecret(CompletableFuture.completedFuture(false));
    }

    /**
     * Retrieves the secret-ness status of the given output.
     */
    public static <T> CompletableFuture<Boolean> isSecretAsync(Output<T> output) {
        Objects.requireNonNull(output);
        return output.isSecretAsync();
    }

    /**
     * Create an @see {@link Output<T>} from an @see {@link Input<T>},
     * with the underlying data unchanged. The value is not copied.
     *
     * @param input the @see {@link Input<T>} to convert to an @see {@link Output<T>}
     * @param <T>   the @see {@link Output<T>} and @see {@link Input<T>} value type
     * @return an @see {@link Output<T>} from an @see {@link Input<T>}
     */
    public static <T> Output<T> create(Input<T> input) {
        return input.toOutput();
    }

    private static <T> Output<T> create(CompletableFuture<T> value, boolean isSecret) {
        Objects.requireNonNull(value);

        var outputData = value.thenApply(
                v -> OutputData.create(ImmutableSet.of(), v, true, isSecret));
        return new Output<>(outputData);
    }

    public static <T> Output<T> internalCreateEmpty() {
        return new Output<>(CompletableFuture.completedFuture(
                OutputData.create(ImmutableSet.of(), null, true, false)
        ));
    }

    /* package */
    static <T> Output<T> internalCreateUnknown(T value) {
        return Outputs.internalCreateRaw(ImmutableSet.of(), value, false, false);
    }

    /* package */
    static <T> Output<T> internalCreateUnknown(Supplier<CompletableFuture<T>> valueFactory) {
        return internalCreateEmpty().applyFuture(ignore -> valueFactory.get());
    }

    /* package */
    static <T> Output<T> internalUnknown(T value) {
        return new Output<>(unknownHelperAsync(value));
    }

    private static <T> CompletableFuture<OutputData<T>> unknownHelperAsync(T value) {
        return CompletableFuture.completedFuture(OutputData.create(ImmutableSet.of(), value, false, false));
    }

    // TODO: find a way to hide this internal interface
    public static <T> Output<T> internalCreateRaw(Set<Resource> resources, @Nullable T value, boolean isKnown, boolean isSecret) {
        var outputData = CompletableFuture.completedFuture(
                OutputData.create(ImmutableSet.copyOf(resources), value, isKnown, isSecret));
        return new Output<>(outputData);
    }

    /**
     * Combines all the @see {@link io.pulumi.core.Input<T>} values in {@code inputs} into a single @see {@link Output}
     * with an @see {@link java.util.List<T>} containing all their underlying values.
     * <p>
     * If any of the {@link io.pulumi.core.Input<T>}s are not known, the final result will be not known.
     * Similarly, if any of the {@link io.pulumi.core.Input<T>}s are secrets, then the final result will be a secret.
     */
    @SafeVarargs // safe because we only call List.of, that is also @SafeVarargs
    public static <T> Output<List<T>> allInputs(Input<T>... inputs) {
        return allInputs(List.of(inputs));
    }

    /**
     * @see Outputs#allInputs(Input[]) for more details.
     */
    public static <T> Output<List<T>> allInputs(Iterable<Input<T>> inputs) {
        return allInputs(Lists.newArrayList(inputs));
    }

    /**
     * Combines all the @see {@link Output<T>} values in {@code outputs}
     * into a single @see {@link Output<T>} with an @see {@link java.util.List<T>}
     * containing all their underlying values.
     * <p/>
     * If any of the @see {@link Output<T>}s are not known, the final result will be not known.
     * Similarly, if any of the @see {@link Output<T>}s are secrets, then the final result will be a secret.
     */
    @SafeVarargs // safe because we only call List.of, that is also @SafeVarargs
    public static <T> Output<List<T>> allOutputs(Output<T>... outputs) {
        return allOutputs(List.of(outputs));
    }

    /**
     * @see Outputs#allOutputs(Output[])  for more details.
     */
    public static <T> Output<List<T>> allOutputs(Iterable<Output<T>> outputs) {
        return allOutputs(Lists.newArrayList(outputs));
    }

    private static <T> Output<List<T>> allInputs(List<Input<T>> inputs) {
        return new Output<>(internalAllInputs(inputs));
    }

    private static <T> Output<List<T>> allOutputs(List<Output<T>> outputs) {
        return new Output<>(internalAllOutputs(outputs));
    }

    /**
     * Takes in a "formattableString" with potential @see {@link Input}s or @see {@link Output}
     * in the 'placeholder holes'. Conceptually, this method unwraps all the underlying values in the holes,
     * combines them appropriately with the "formattableString", and produces an @see {@link Output}
     * containing the final result.
     * <p>
     * If any of the @see {@link Input}s or {@link Output}s are not known, the
     * final result will be not known.
     * <p>
     * Similarly, if any of the @see {@link Input}s or @see {@link Input}s are secrets,
     * then the final result will be a secret.
     */
    public static Output<String> format(String formattableString, Object... arguments) {
        var inputs = Lists.newArrayList(arguments)
                .stream()
                .map(Input::internalToObjectInput)
                .collect(Collectors.toList());

        return allInputs(inputs).apply(objs -> String.format(formattableString, objs.toArray()));
    }

    @Internal
    static <T> Output<List<T>> internalConcat(Output<List<T>> values1, Output<List<T>> values2) {
        Objects.requireNonNull(values1);
        Objects.requireNonNull(values2);
        return Outputs.tuple(values1, values2).apply(
                t -> Stream
                        .concat(t.t1.stream(), t.t2.stream())
                        .collect(Collectors.toList())
        );
    }


    // Tuple Overloads that take different numbers of inputs or outputs.

    private static final Input<Void> ZeroIn = Inputs.internalCreateEmpty();
    private static final Output<Void> ZeroOut = Outputs.internalCreateEmpty();

    /**
     * @see Outputs#tuple(Input, Input, Input, Input, Input, Input, Input, Input)
     */
    public static <T1, T2> Output<Tuple2<T1, T2>> tuple(Input<T1> item1, Input<T2> item2) {
        return tuple(item1, item2, ZeroIn, ZeroIn, ZeroIn, ZeroIn, ZeroIn, ZeroIn).apply(v -> Tuples.of(v.t1, v.t2));
    }

    /**
     * @see Outputs#tuple(Input, Input, Input, Input, Input, Input, Input, Input)
     */
    public static <T1, T2, T3> Output<Tuple3<T1, T2, T3>> tuple(
            Input<T1> item1, Input<T2> item2, Input<T3> item3
    ) {
        return tuple(item1, item2, item3, ZeroIn, ZeroIn, ZeroIn, ZeroIn, ZeroIn).apply(
                v -> Tuples.of(v.t1, v.t2, v.t3)
        );
    }

    /**
     * @see Outputs#tuple(Input, Input, Input, Input, Input, Input, Input, Input)
     */
    public static <T1, T2, T3, T4> Output<Tuple4<T1, T2, T3, T4>> tuple(
            Input<T1> item1, Input<T2> item2, Input<T3> item3, Input<T4> item4
    ) {
        return tuple(item1, item2, item3, item4, ZeroIn, ZeroIn, ZeroIn, ZeroIn).apply(
                v -> Tuples.of(v.t1, v.t2, v.t3, v.t4)
        );
    }

    /**
     * @see Outputs#tuple(Input, Input, Input, Input, Input, Input, Input, Input)
     */
    public static <T1, T2, T3, T4, T5> Output<Tuple5<T1, T2, T3, T4, T5>> tuple(
            Input<T1> item1, Input<T2> item2, Input<T3> item3, Input<T4> item4, Input<T5> item5
    ) {
        return tuple(item1, item2, item3, item4, item5, ZeroIn, ZeroIn, ZeroIn).apply(
                v -> Tuples.of(v.t1, v.t2, v.t3, v.t4, v.t5)
        );
    }

    /**
     * @see Outputs#tuple(Input, Input, Input, Input, Input, Input, Input, Input)
     */
    public static <T1, T2, T3, T4, T5, T6> Output<Tuple6<T1, T2, T3, T4, T5, T6>> tuple(
            Input<T1> item1, Input<T2> item2, Input<T3> item3, Input<T4> item4,
            Input<T5> item5, Input<T6> item6
    ) {
        return tuple(item1, item2, item3, item4, item5, item6, ZeroIn, ZeroIn).apply(
                v -> Tuples.of(v.t1, v.t2, v.t3, v.t4, v.t5, v.t6)
        );
    }

    /**
     * @see Outputs#tuple(Input, Input, Input, Input, Input, Input, Input, Input)
     */
    public static <T1, T2, T3, T4, T5, T6, T7> Output<Tuple7<T1, T2, T3, T4, T5, T6, T7>> tuple(
            Input<T1> item1, Input<T2> item2, Input<T3> item3, Input<T4> item4,
            Input<T5> item5, Input<T6> item6, Input<T7> item7
    ) {
        return tuple(item1, item2, item3, item4, item5, item6, item7, ZeroIn).apply(
                v -> Tuples.of(v.t1, v.t2, v.t3, v.t4, v.t5, v.t6, v.t7)
        );
    }

    /**
     * Combines all the @see {@link Input} values in the provided parameters and combines
     * them all into a single tuple containing each of their underlying values.
     * If any of the @see {@link Input}s are not known, the final result will be not known.  Similarly,
     * if any of the @see {@link Input}s are secrets, then the final result will be a secret.
     */
    public static <T1, T2, T3, T4, T5, T6, T7, T8> Output<Tuple8<T1, T2, T3, T4, T5, T6, T7, T8>> tuple(
            Input<T1> item1, Input<T2> item2, Input<T3> item3, Input<T4> item4,
            Input<T5> item5, Input<T6> item6, Input<T7> item7, Input<T8> item8
    ) {
        return new Output<>(OutputData.tuple(item1, item2, item3, item4, item5, item6, item7, item8));
    }

    /**
     * @see Outputs#tuple(Output, Output, Output, Output, Output, Output, Output, Output)
     */
    public static <T1, T2> Output<Tuple2<T1, T2>> tuple(Output<T1> item1, Output<T2> item2) {
        return tuple(item1, item2, ZeroOut, ZeroOut, ZeroOut, ZeroOut, ZeroOut, ZeroOut).apply(v -> Tuples.of(v.t1, v.t2));
    }

    /**
     * @see Outputs#tuple(Output, Output, Output, Output, Output, Output, Output, Output)
     */
    public static <T1, T2, T3> Output<Tuple3<T1, T2, T3>> tuple(
            Output<T1> item1, Output<T2> item2, Output<T3> item3
    ) {
        return tuple(item1, item2, item3, ZeroOut, ZeroOut, ZeroOut, ZeroOut, ZeroOut).apply(
                v -> Tuples.of(v.t1, v.t2, v.t3)
        );
    }

    /**
     * @see Outputs#tuple(Output, Output, Output, Output, Output, Output, Output, Output)
     */
    public static <T1, T2, T3, T4> Output<Tuple4<T1, T2, T3, T4>> tuple(
            Output<T1> item1, Output<T2> item2, Output<T3> item3, Output<T4> item4
    ) {
        return tuple(item1, item2, item3, item4, ZeroOut, ZeroOut, ZeroOut, ZeroOut).apply(
                v -> Tuples.of(v.t1, v.t2, v.t3, v.t4)
        );
    }

    /**
     * @see Outputs#tuple(Output, Output, Output, Output, Output, Output, Output, Output)
     */
    public static <T1, T2, T3, T4, T5> Output<Tuple5<T1, T2, T3, T4, T5>> tuple(
            Output<T1> item1, Output<T2> item2, Output<T3> item3, Output<T4> item4,
            Output<T5> item5
    ) {
        return tuple(item1, item2, item3, item4, item5, ZeroOut, ZeroOut, ZeroOut).apply(
                v -> Tuples.of(v.t1, v.t2, v.t3, v.t4, v.t5)
        );
    }

    /**
     * @see Outputs#tuple(Output, Output, Output, Output, Output, Output, Output, Output)
     */
    public static <T1, T2, T3, T4, T5, T6> Output<Tuple6<T1, T2, T3, T4, T5, T6>> tuple(
            Output<T1> item1, Output<T2> item2, Output<T3> item3, Output<T4> item4,
            Output<T5> item5, Output<T6> item6
    ) {
        return tuple(item1, item2, item3, item4, item5, item6, ZeroOut, ZeroOut).apply(
                v -> Tuples.of(v.t1, v.t2, v.t3, v.t4, v.t5, v.t6)
        );
    }

    /**
     * @see Outputs#tuple(Output, Output, Output, Output, Output, Output, Output, Output)
     */
    public static <T1, T2, T3, T4, T5, T6, T7> Output<Tuple7<T1, T2, T3, T4, T5, T6, T7>> tuple(
            Output<T1> item1, Output<T2> item2, Output<T3> item3, Output<T4> item4,
            Output<T5> item5, Output<T6> item6, Output<T7> item7
    ) {
        return tuple(item1, item2, item3, item4, item5, item6, item7, ZeroOut).apply(
                v -> Tuples.of(v.t1, v.t2, v.t3, v.t4, v.t5, v.t6, v.t7)
        );
    }

    /**
     * Combines all the @see {@link Output} values in the provided parameters and combines
     * them all into a single tuple containing each of their underlying values.
     * If any of the @see {@link Output}s are not known, the final result will be not known.  Similarly,
     * if any of the @see {@link Output}s are secrets, then the final result will be a secret.
     */
    public static <T1, T2, T3, T4, T5, T6, T7, T8> Output<Tuple8<T1, T2, T3, T4, T5, T6, T7, T8>> tuple(
            Output<T1> item1, Output<T2> item2, Output<T3> item3, Output<T4> item4,
            Output<T5> item5, Output<T6> item6, Output<T7> item7, Output<T8> item8
    ) {
        return new Output<>(OutputData.tuple(item1, item2, item3, item4, item5, item6, item7, item8));
    }
}
