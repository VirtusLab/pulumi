package io.pulumi.deployment.internal;


import io.pulumi.deployment.InvokeOptions;
import io.pulumi.resources.InvokeArgs;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

public interface DeploymentInternal {

    /**
     * @return the current stack name
     */
    @Nonnull
    String getStackName();

    /**
     * @return the current project name
     */
    @Nonnull
    String getProjectName();

    /**
     * Whether or not the application is currently being previewed or actually applied.
     * @return true if application is being applied
     */
    boolean isDryRun();

    /**
     * Dynamically invokes the function {@code token}, which is offered by a provider plugin.
     *
     * The result of {@code invokeAsync} will be a @see {@link CompletableFuture} resolved to the
     * result value of the provider plugin.
     *
     * The {@code args} inputs can be a bag of computed values
     * (including, {@code T}s, @see {@link CompletableFuture}, @see {@link io.pulumi.core.Output}, s etc.).
     */
    <T> CompletableFuture<T> invokeAsync(String token, InvokeArgs args, @Nullable InvokeOptions options);

    /**
     * Same as @see {@link #invokeAsync(String, InvokeArgs, InvokeOptions)}, however the return value is ignored.
     */
    CompletableFuture<Void> invokeAsyncIgnore(String token, InvokeArgs args, @Nullable InvokeOptions options);
}
