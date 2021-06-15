package io.pulumi.core;

import io.pulumi.serialization.OutputData;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class Output<T> {
    private final CompletableFuture<OutputData<T>> dataFuture;

    private Output(CompletableFuture<OutputData<T>> dataFuture) {
        this.dataFuture = dataFuture;

        /* TODO: wut?!
        if (Deployment.TryGetInternalInstance(out var instance))
        {
            instance.Runner.RegisterTask("Output<>", dataTask);
        }*/
    }

    public static <T> Output<T> create(T value) {
        return create(CompletableFuture.completedFuture(value));
    }

    public static <T> Output<T> create(CompletableFuture<T> value) {
        return create(value, false);
    }

    public static <T> Output<T> create(Input<T> value) {
        return value.outputValue;
    }

    public static <T> Output<T> CreateSecret(T value) {
        return CreateSecret(CompletableFuture.completedFuture(value));
    }

    public static <T> Output<T> CreateSecret(CompletableFuture<T> value) {
        return create(value, true);
    }

    private static <T> Output<T> create(CompletableFuture<T> value, boolean isSecret) {
        Objects.requireNonNull(value);

        // TODO: wut?!
        /*var tcs = new TaskCompletionSource<OutputData<T>>();
        value.Assign(tcs, t => OutputData.Create(ImmutableHashSet<Resource>.Empty, t, isKnown: true, isSecret: isSecret));
        return new Output<T>(tcs.Task);*/
        throw new UnsupportedOperationException("unimplemented");
    }

    // TODO
}
