package io.pulumi.core;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import io.grpc.Internal;
import io.pulumi.core.internal.InputOutputData;
import io.pulumi.core.internal.InputOutputImpl;
import io.pulumi.resources.Resource;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static io.pulumi.core.internal.InputOutputData.internalAllHelperAsync;

@Internal
@ParametersAreNonnullByDefault
public class OutputDefault<T> extends InputOutputImpl<T, Output<T>> implements Output<T> {

    @Internal
    static final Output<Void> ZeroOut = Output.empty();

    protected OutputDefault(CompletableFuture<InputOutputData<T>> dataFuture) {
        super(dataFuture);

        /* TODO: wut?!
        if (Deployment.TryGetInternalInstance(out var instance))
        {
            instance.Runner.RegisterTask("Output<>", dataTask);
        }*/
    }

    @Override
    protected Output<T> newInstance(CompletableFuture<InputOutputData<T>> dataFuture) {
        return new OutputDefault<>(dataFuture);
    }

    public Input<T> toInput() {
        return new InputDefault<>(dataFuture.copy());
    }

    public <U> Output<U> applyOutput(Function<T, Output<U>> func) {
        return new OutputDefault<>(InputOutputData.apply(dataFuture, func.andThen(
                o -> ((InputOutputImpl<U, Output<U>>) o).internalGetDataAsync())));
    }

    // TODO

    // Static section -----

    @Internal
    public static <T> Output<T> of(Set<Resource> resources, T value) {
        Objects.requireNonNull(value);
        return new OutputDefault<>(CompletableFuture.completedFuture(
                InputOutputData.of(ImmutableSet.copyOf(resources), value)));
    }

    @Internal
    private static <T> Output<T> internalUnknown(T value) {
        return new OutputDefault<>(CompletableFuture.completedFuture(InputOutputData.unknown(value)));
    }

    @Internal
    private static <T> Output<T> internalUnknown(Supplier<CompletableFuture<T>> valueFactory) {
        return Output.empty().applyFuture(ignore -> valueFactory.get());
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
        var data = Lists.newArrayList(arguments)
                .stream()
                .map(InputOutputData::internalCopyInputOutputData)
                .collect(Collectors.toList());

        return new OutputDefault<>(
                internalAllHelperAsync(data)
                        .thenApply(objs -> objs.withValue(
                                v -> v == null ? null : String.format(formattableString, v.toArray())))
        );
    }

}
