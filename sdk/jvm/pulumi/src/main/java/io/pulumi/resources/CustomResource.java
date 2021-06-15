package io.pulumi.resources;

import io.pulumi.core.Output;

import javax.annotation.Nullable;

public class CustomResource extends Resource {
    private Output<String> id;

    public CustomResource(String type, String name, @Nullable ResourceArgs args, @Nullable CustomResourceOptions options) {
        this(type, name, args, options, false);
    }

    private CustomResource(
            String type, String name, @Nullable ResourceArgs args, @Nullable CustomResourceOptions options, boolean dependency) {
        super(type, name, true, args == null ? ResourceArgs.EMPTY : args, options == null ? CustomResourceOptions.EMPTY : options, false, dependency);
    }

    public Output<String> getId() {
        return id;
    }

    protected void setId(Output<String> id) {
        this.id = id;
    }
}
