package io.pulumi.resources;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.pulumi.core.Input;
import io.pulumi.core.Output;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class Resource {
    private final String type;
    private final String name;

    private final Optional<Resource> parent;

    private final Set<Resource> childResources = new HashSet<>();

    public final Output<String> Urn;

    private final boolean protect;

    private final ImmutableList<ResourceTransformation> transformations;

    private final ImmutableList<Input<String>> aliases;

    public String getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    private final ImmutableMap<String, ProviderResource> providers;

    protected Resource(String type, String name, boolean custom,
                       ResourceArgs args, ResourceOptions options) {
        this(type, name, custom, args, options, false, false);
    }

    protected Resource(
            String type, String name, boolean custom,
            ResourceArgs args, ResourceOptions options,
            boolean remote, boolean dependency)
    {
        // TODO
    }
}
