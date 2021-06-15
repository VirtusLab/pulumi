package io.pulumi.deployment;

import io.pulumi.resources.InvokeArgs;
import io.pulumi.resources.ProviderResource;
import io.pulumi.resources.Resource;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Options to help control the behavior of @see {@link io.pulumi.deployment.internal.DeploymentInternal#invokeAsync(String, InvokeArgs, InvokeOptions)}/>.
 */
public class InvokeOptions {
    @Nullable
    private final Resource parent;
    @Nullable
    private final ProviderResource provider;
    @Nullable
    private final String version;

    public InvokeOptions(@Nullable Resource parent, @Nullable ProviderResource provider, @Nullable String version) {
        this.parent = parent;
        this.provider = provider;
        this.version = version;
    }

    /**
     * An optional parent to use for default options for this invoke (e.g. the default provider
     * to use).
     */
    public Optional<Resource> getParent() {
        return Optional.ofNullable(parent);
    }

    /*
     * An optional provider to use for this invocation. If no provider is supplied, the default
     * provider for the invoked function's package will be used.
     */
    public Optional<ProviderResource> getProvider() {
        return Optional.ofNullable(provider);
    }

    /**
     * An optional version, corresponding to the version of the provider plugin that should be
     * used when performing this invoke.
     */
    public Optional<String> getVersion() {
        return Optional.ofNullable(version);
    }

    /* package */ Optional<ProviderResource> internalGetProvider(String token) {
        return Optional.ofNullable(
                this.provider != null
                        ? this.provider
                        : this.parent != null ? this.parent.internalGetProvider(token) : null
        );
    }
}
