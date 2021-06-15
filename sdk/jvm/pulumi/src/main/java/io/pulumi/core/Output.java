package io.pulumi.core;

import io.grpc.Internal;
import io.pulumi.core.internal.OutputData;
import io.pulumi.core.internal.UntypedOutput;
import io.pulumi.resources.Resource;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

@ParametersAreNonnullByDefault
public class Output<T> implements UntypedOutput {
    private final CompletableFuture<OutputData<T>> dataFuture;

    /* package */ Output(CompletableFuture<OutputData<T>> dataFuture) {
        this.dataFuture = dataFuture;

        /* TODO: wut?!
        if (Deployment.TryGetInternalInstance(out var instance))
        {
            instance.Runner.RegisterTask("Output<>", dataTask);
        }*/
    }

    // TODO: needed?
    /*
    @Internal
    public static <T> Output<T> internalUnknown(@Nullable T value) {
        var outputData = CompletableFuture.completedFuture(
                OutputData.create(ImmutableSet.of(), value, false, false));
        return new Output<>(outputData);
    }*/

    @Internal
    public CompletableFuture<OutputData<T>> internalGetDataAsync() {
        return this.dataFuture;
    }

    @Internal
    public CompletableFuture<T> internalGetValueAsync() {
        return this.dataFuture.thenApply(OutputData::getValue);
    }

    @Override
    @Internal
    public CompletableFuture<Set<Resource>> internalGetResourcesUntypedAsync() {
        return this.dataFuture.thenApply(OutputData::getResources);
    }

    @SuppressWarnings("rawtypes")
    @Override
    @Internal
    public CompletableFuture<OutputData> internalGetDataUntypedAsync() {
        return this.dataFuture.thenApply(Function.<OutputData>identity());
    }

    /* package */ Output<T> internalWithIsSecret(CompletableFuture<Boolean> isSecretFuture) {
        return new Output<>(
                isSecretFuture.thenCompose(
                    secret -> this.dataFuture.thenApply(
                        d -> d.withIsSecret(secret)
                    )
                )
        );
    }

    /**
     * @return the secret-ness status of the given output
     */
    public CompletableFuture<Boolean> isSecretAsync() {
        return this.dataFuture.thenApply(OutputData::isSecret);
    }

    /**
     * @return true if the given output is empty (null)
     */
    public CompletableFuture<Boolean> isEmpty() {
        return this.dataFuture.thenApply(OutputData::isEmpty);
    }

    /**
     * Creates a shallow copy (the underlying CompletableFuture is copied) of this @see {@link Output<T>}
     * @return a shallow copy of the @see {@link Output<T>}
     */
    public Output<T> copy() {
        // we do not copy the OutputData, because it should be immutable
        return new Output<>(this.dataFuture.copy()); // TODO: is the copy deep enough
    }

    /**
     * Convert @see {@link Output<T>} to @see {@link Input<T>}
     * @return an {@link Input<T>} , converted from {@link Output<T>}
     */
    public Input<T> toInput() {
        return Inputs.create(this);
    }

    /**
     * @see Output#applyOutput(Function) for more details.
     */
    public <U> Output<U> apply(Function<T, U> func) {
        return applyOutput(t -> Outputs.create(func.apply(t)));
    }


    /**
     * @see Output#applyOutput(Function) for more details.
     */
    public <U> Output<U> applyFuture(Function<T, CompletableFuture<U>> func) {
        return applyOutput(t -> Outputs.create(func.apply(t)));
    }


    /**
     * @see Output#applyOutput(Function) for more details.
     */
    public <U> Output<U> applyInput(Function<T, Input<U>> func) {
        return applyOutput(t -> Outputs.create(func.apply(t)));
    }

    /**
     * Transforms the data of this @see {@link Output<T>} with the provided {@code func}.
     * The result remains an @see {@link Output<T>} so that dependent resources
     * can be properly tracked.
     * <p/>
     * {@code func} is not allowed to make resources.
     * <p/>
     * {@code func} can return other @see {@link Output<T>}s.  This can be handy if
     * you have an <code>Output&lt;SomeType&gt;</code> and you want to get a transitive dependency of it.  i.e.:
     * <br/>
     * <code>
     * Output&lt;SomeType&gt; d1 = ...;
     * Output&lt;OtherType&gt; d2 = d1.apply(v -> v.otherOutput); // getting an output off of 'v'.
     * </code>
     * <p/>
     * In this example, taking a dependency on d2 means a resource will depend on all the resources
     * of d1.  It will <b>not</b> depend on the resources of v.x.y.OtherDep.
     * <p/>
     * Importantly, the Resources that d2 feels like it will depend on are the same resources
     * as d1.
     * <p/>
     * If you need have multiple @see {@link Output<T>}s and a single @see {@link Output<T>}
     * is needed that combines both set of resources, then @see {@link Outputs#allInputs(Input[])}
     * or {@link Outputs#tuple(Input, Input, Input)} should be used instead.
     * <p/>
     * This function will only be called during execution of a <code>pulumi up</code> request.
     * It will not run during <code>pulumi preview</code>
     * (as the values of resources are of course not known then).
     */
    public <U> Output<U> applyOutput(Function<T, Output<U>> func) {
        return new Output<>(OutputData.apply(dataFuture, func.andThen(o -> o.dataFuture)));
    }

    // TODO

}
