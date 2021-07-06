package io.pulumi;

import io.grpc.Internal;
import io.pulumi.core.Output;
import io.pulumi.deployment.Deployment;
import io.pulumi.resources.ComponentResource;
import io.pulumi.resources.ComponentResourceOptions;
import io.pulumi.resources.Resource;
import io.pulumi.resources.StackOptions;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

public class Stack extends ComponentResource {
    /**
     * Constant to represent the 'root stack' resource for a Pulumi application.
     * The purpose of this is solely to make it easy to write an @see {@link io.pulumi.core.Alias} like so:
     * <p>
     * <code>aliases = { new Alias(..., /* parent *&#47; Stack.InternalRoot, ... } }</code>
     * </p>
     * This indicates that the prior name for a resource was created based on it being parented
     * directly by the stack itself and no other resources. Note: this is equivalent to:
     * <p>
     * <code>aliases = { new Alias(..., /* parent *&#47; null, ...) }</code>
     * </p>
     * However, the former form is preferable as it is more self-descriptive, while the latter
     * may look a bit confusing and may incorrectly look like something that could be removed
     * without changing semantics.
     */
    @Nullable
    @Internal
    public static final Resource InternalRoot = null;

    /**
     * The type name that should be used to construct the root component in the tree of Pulumi resources
     * allocated by a deployment. This must be kept up to date with
     * "github.com/pulumi/pulumi/sdk/v3/go/common/resource/stack.RootStackType".
     */
    @Internal
    public static final String InternalRootPulumiStackTypeName = "pulumi:pulumi:Stack";

    /**
     * The outputs of this stack, if the <code>init</code> callback exited normally.
     */
    private Output<Map<String, Optional<Object>>> outputs = Output.of(Map.of());

    /**
     * Create a Stack with stack resources defined in derived class constructor.
     *
     * @param options optional stack options
     */
    public Stack(@Nullable StackOptions options) {
        this(() -> CompletableFuture.completedFuture(Map.of()), options);
    }

    /**
     * Create a Stack with stack resources created by the <code>init</code> callback.
     * An instance of this will be automatically created when
     * any @see {@link Deployment#runAsync(Runnable)} overload is called.
     */
    @Internal
    public Stack(Supplier<CompletableFuture<Map<String, Optional<Object>>>> init, @Nullable StackOptions options) {
        super(
                InternalRootPulumiStackTypeName,
                String.format("%s-%s", Deployment.getInstance().getProjectName(), Deployment.getInstance().getStackName()),
                convertOptions(options)
        );
        // TODO: wut?!
        // Deployment.InternalInstance.Stack = this;

        try {
            this.outputs = Output.of(runInitAsync(init));
        } finally {
            this.registerOutputs(this.outputs);
        }
    }

    @Internal
    public Output<Map<String, Optional<Object>>> internalGetOutputs() {
        return outputs;
    }

    /**
     * Inspect all public properties of the stack to find outputs.
     * Validate the values and register them as stack outputs.
     */
    @Internal
    public void internalRegisterPropertyOutputs() {
        // TODO

        Map<String, Optional<Object>> dict = Map.of();
        this.outputs = Output.of(dict);
        this.registerOutputs(this.outputs);
    }

    private static CompletableFuture<Map<String, Optional<Object>>> runInitAsync(
            Supplier<CompletableFuture<Map<String, Optional<Object>>>> init
    ) {
        return init.get().thenApply(Function.identity());
    }

    @Nullable
    private static ComponentResourceOptions convertOptions(@Nullable StackOptions options) {
        if (options == null) {
            return null;
        }

        return new ComponentResourceOptions(
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                options.getResourceTransformations(),
                null,
                null,
                null
        );
    }
}
