package io.pulumi.resources;

import javax.annotation.Nullable;

public class ComponentResource extends Resource {
    public ComponentResource(String type, String name, ComponentResourceOptions options) {
        this(type, name, ResourceArgs.EMPTY, options, false);
    }

    public ComponentResource(String type, String name, ComponentResourceOptions options, boolean remote) {
        this(type, name, ResourceArgs.EMPTY, options, remote);
    }

    public ComponentResource(String type, String name, @Nullable ResourceArgs args, @Nullable ComponentResourceOptions options, boolean remote) {
        super(type, name, false, args == null ? ResourceArgs.EMPTY : args, options == null ? new ComponentResourceOptions() : options, remote, dependency);
    }

    // TODO
}
