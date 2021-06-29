package io.pulumi.resources;

import java.util.Optional;

import com.google.common.collect.ImmutableList;

public final class ComponentResourceOptions extends ResourceOptions {
    private Optional<ImmutableList<ProviderResource>> providers;
    public ImmutableList<ProviderResource> getProviders() {
        if (!providers.isEmpty()) {
            return providers.get();
        } else {
            return ImmutableList.of();
        }
    }
    public ComponentResourceOptions clone() {
        ComponentResourceOptions clone = new ComponentResourceOptions();
        clone.copyValues(this);
        clone.providers = this.providers;
        return clone;
    }
}
