package io.pulumi.core;

import io.pulumi.serialization.OutputData;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import io.pulumi.deployment.Deployment;
import io.pulumi.resources.Resource;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;



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

    public static <T> Output<T> createSecret(T value) {
        return createSecret(CompletableFuture.completedFuture(value));
    }

    public static <T> Output<T> createSecret(CompletableFuture<T> value) {
        return create(value, true);
    }

    // internal Output<T> WithIsSecret(Task<bool> isSecret)
    // {
    //     async Task<OutputData<T>> GetData()
    //     {
    //         var data = await this.DataTask.ConfigureAwait(false);
    //         return new OutputData<T>(data.Resources, data.Value, data.IsKnown, await isSecret.ConfigureAwait(false));
    //     }

    //     return new Output<T>(GetData());
    // }

    private static <T> Output<T> create(CompletableFuture<T> value, boolean isSecret) {
        Objects.requireNonNull(value);

        // TODO: wut?!
        /*var tcs = new TaskCompletionSource<OutputData<T>>();
        value.Assign(tcs, t => OutputData.Create(ImmutableHashSet<Resource>.Empty, t, isKnown: true, isSecret: isSecret));
        return new Output<T>(tcs.Task);*/


        CompletableFuture<OutputData<T>> outputData = value.thenApply(v -> OutputData.create(ImmutableSet.of(), v, true, isSecret));
        return new Output<T>(outputData);
    }

    private CompletableFuture<T> getValueAsync() {
        return CompletableFuture.supplyAsync(() -> getDataAsync().join().getValue());
    }

    private CompletableFuture<ImmutableSet<Resource>> getResourceAsync() {
        return CompletableFuture.supplyAsync(() -> getDataAsync().join().getResources());
    }

    private CompletableFuture<OutputData<T>> getDataAsync() {
        return dataFuture;
    }

    public <U> Output<U> apply(Function<T, U> func) {
        return applyOutput(t -> Output.create(func.apply(t)));
    }

    public <U> Output<U> applyFuture(Function<T, CompletableFuture<U>> func) {
        return applyOutput(t -> Output.create(func.apply(t)));
    }

    public <U> Output<U> applyInput(Function<T, Input<U>> func) {
        return applyOutput(t -> Output.create(func.apply(t)));
    }

    public <U> Output<U> applyOutput(Function<T, Output<U>> func) {
        return new Output<U>(applyHelperAsync(dataFuture, func));
    }

    private static <T, U> CompletableFuture<OutputData<U>> applyHelperAsync(CompletableFuture<OutputData<T>> dataFuture, Function<T, Output<U>> func){
        return CompletableFuture.supplyAsync(() -> {
            OutputData<T> data = dataFuture.join();
            ImmutableSet<Resource> resources = data.getResources();
            if (!data.isKnown() && Deployment.INSTANCE.isDryRun()) {
                return OutputData.create(resources, (U) null, data.isKnown(), data.isSecret());
            }
            Output<U> inner = func.apply(data.getValue());
            if (inner == null) {
                return OutputData.create(resources, (U) null, data.isKnown(), data.isSecret());
            }

            OutputData<U> innerData = inner.getDataAsync().join();
            return OutputData.create(ImmutableSet.copyOf(Sets.union(resources, innerData.getResources())), innerData.getValue(), data.isKnown() && innerData.isKnown(), data.isSecret() || innerData.isSecret());
        });
    }

    //    /// <summary>
    //     /// Returns a new <see cref="Output{T}"/> which is a copy of the existing output but marked as
    //     /// a non-secret. The original output is not modified in any way.
    //     /// </summary>
    //     public static Output<T> Unsecret<T>(Output<T> output)
    //         => output.WithIsSecret(Task.FromResult(false));

    //     /// <summary>
    //     /// Retrieves the secretness status of the given output.
    //     /// </summary>
    //     public static async Task<bool> IsSecretAsync<T>(Output<T> output)
    //     {
    //         var dataTask = await output.DataTask.ConfigureAwait(false);
    //         return dataTask.IsSecret;
    //     }

    // TODO: all, tuple. format, concat

    public static Output<String> format(String format, Input<String> ... args) { // TODO: inputs or outputs?
        // TODO:
        return Output.create("");
    }
}
