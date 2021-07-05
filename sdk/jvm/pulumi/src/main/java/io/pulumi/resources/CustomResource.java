package io.pulumi.resources;

import io.pulumi.core.Output;
import io.pulumi.core.Outputs;

import javax.annotation.Nullable;

public class CustomResource extends Resource {
    @Nullable
    private /* final */ Output<String> id; // can be set only once, with the setter

    public CustomResource(String type, String name, @Nullable ResourceArgs args, @Nullable CustomResourceOptions options) {
        this(type, name, args, options, false);
    }

    public CustomResource(String type, String name, @Nullable ResourceArgs args, boolean dependency) {
        this(type, name, args, null, dependency);
    }

    protected CustomResource(
            String type,
            String name,
            @Nullable ResourceArgs args,
            @Nullable CustomResourceOptions options,
            boolean dependency
    ) {
        super(type, name, true,
                args == null ? ResourceArgs.Empty : args,
                options == null ? CustomResourceOptions.Empty : options,
                false, dependency);
    }

    public Output<String> getId() {
        return this.id == null ? Outputs.internalCreateEmpty() : this.id;
    }

    protected void setId(@Nullable Output<String> id) {
        if (this.id == null) {
            this.id = id;
        } else {
            throw new IllegalStateException("id cannot be set twice, must be null for setId to work");
        }
    }
}
