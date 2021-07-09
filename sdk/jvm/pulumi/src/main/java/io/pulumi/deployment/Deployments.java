package io.pulumi.deployment;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import io.grpc.Internal;
import io.pulumi.deployment.internal.Runner;
import io.pulumi.resources.StackOptions;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

public class Deployments {

    protected Deployments() {}

    /**
     * @param callback Callback that creates stack resources.
     * @see #runAsyncFuture(Supplier, StackOptions) for more details.
     */
    public static CompletableFuture<Integer> runAsyncRunnable(Runnable callback) {
        return runAsync(() -> {
            callback.run();
            return ImmutableMap.of();
        });
    }

    /**
     * @param callback Callback that creates stack resources.
     * @return A dictionary of stack outputs.
     * @see #runAsyncFuture(Supplier, StackOptions) for more details.
     */
    public static CompletableFuture<Integer> runAsync(Supplier<Map<String, Optional<Object>>> callback) {
        return runAsyncFuture(() -> CompletableFuture.supplyAsync(callback));
    }

    /**
     * @param callback Callback that creates stack resources.
     * @see #runAsyncFuture(Supplier, StackOptions) for more details.
     */
    public static CompletableFuture<Integer> runAsyncFutureVoid(Supplier<CompletableFuture<Void>> callback) {
        return runAsyncFuture(() -> callback.get()
                .thenApply(ignore -> ImmutableMap.of())
        );
    }

    /**
     * @param callback Callback that creates stack resources.
     * @see #runAsyncFuture(Supplier, StackOptions) for more details.
     */
    public static CompletableFuture<Integer> runAsyncFuture(
            Supplier<CompletableFuture<Map<String, Optional<Object>>>> callback
    ) {
        return runAsyncFuture(callback, null);
    }

    /**
     * @param callback Callback that creates stack resources.
     * @param options  optional Stack options.
     * @see #runAsyncFuture(Supplier, StackOptions) is an entry-point to a Pulumi application.
     * JVM applications should perform all startup logic
     * they need in their {@code Main} method and then end with:
     * <p>
     * <code>
     * public static void main(String[] args) {
     * // program initialization code ...
     * <p>
     * return Deployment.runAsync(() -> {
     * // Code that creates resources.
     * });
     * }
     * </code>
     * </p>
     * Importantly: Cloud resources cannot be created outside of the lambda passed
     * to any of the @see #runAsync overloads.
     * Because cloud Resource construction is inherently asynchronous,
     * the result of this function is a @see {@link CompletableFuture} which should then be returned or awaited.
     * This will ensure that any problems that are encountered during
     * the running of the program are properly reported.
     * Failure to do this may lead to the program ending early before all resources are properly registered.
     * <p/>
     * The function passed to @see #runAsyncFuture(Supplier, StackOptions)
     * can optionally return a @see {@link java.util.Map}.
     * The keys and values in this map will become the outputs for the Pulumi Stack that is created.
     */
    public static CompletableFuture<Integer> runAsyncFuture(
            Supplier<CompletableFuture<Map<String, Optional<Object>>>> callback,
            @Nullable StackOptions options
    ) {
        return internalCreateRunnerAndRunAsync(Deployment::new, runner -> runner.runAsyncFuture(callback, options));
    }

    // TODO

    // this method *must* remain marked async
    // in order to protect the scope of the Deployment#nstance we cannot elide the task (return it early)
    // if the task is returned early and not awaited, than it is possible for any code that runs before the eventual await
    // to be executed synchronously and thus have multiple calls to one of the run methods affecting each others Deployment#instance
    @Internal
    @VisibleForTesting
    static CompletableFuture<Integer> internalCreateRunnerAndRunAsync(
            Supplier<Deployment> deploymentFactory,
            Function<Runner, CompletableFuture<Integer>> runAsync
    ) {
        return CompletableFuture.supplyAsync(deploymentFactory)
                .thenApply(deployment -> {
                    Deployment.setInstance(new DeploymentInstance(deployment));
                    return deployment.getRunner();
                })
                .thenCompose(runAsync);
    }
}
