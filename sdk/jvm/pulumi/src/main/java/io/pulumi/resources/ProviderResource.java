package io.pulumi.resources;

import java.util.Optional;

import javax.annotation.Nullable;

public class ProviderResource extends CustomResource {
    protected final String packageName;
    private Optional<String> registrationId;

    public ProviderResource(String packageName, String name, ResourceArgs args, CustomResourceOptions options) {
        this(packageName, name, args, options, false);
    }

    public ProviderResource(String packageName, String name, ResourceArgs args, @Nullable CustomResourceOptions options, boolean dependency) {
        super(String.format("pulumi:providers:%s", packageName), name, args, options, dependency);
        this.packageName = packageName;
    }
}
