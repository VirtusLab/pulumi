package io.pulumi.deployment;

import io.grpc.Internal;
import io.pulumi.deployment.internal.DeploymentInternal;
import io.pulumi.deployment.internal.DeploymentInternalInternal;
import io.pulumi.resources.InvokeArgs;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

/**
 * Metadata of the deployment that is currently running. Accessible via @see {@link Deployment#getInstance()}.
 */
public class DeploymentInstance implements DeploymentInternal {

    private final DeploymentInternal deployment;

    public DeploymentInstance(DeploymentInternal deployment) {
        this.deployment = deployment;
    }

    @Nonnull
    @Override
    public String getStackName() {
        return deployment.getStackName();
    }

    @Nonnull
    @Override
    public String getProjectName() {
        return deployment.getProjectName();
    }

    @Override
    public boolean isDryRun() {
        return deployment.isDryRun();
    }

    @Override
    public <T> CompletableFuture<T> invokeAsync(String token, InvokeArgs args, @Nullable InvokeOptions options) {
        return deployment.invokeAsync(token, args, options);
    }

    @Override
    public CompletableFuture<Void> invokeAsyncIgnore(String token, InvokeArgs args, @Nullable InvokeOptions options) {
        return deployment.invokeAsyncIgnore(token, args, options);
    }

    @Internal
    public DeploymentInternalInternal getInternal() {
        return (DeploymentInternalInternal) deployment;
    }
}
