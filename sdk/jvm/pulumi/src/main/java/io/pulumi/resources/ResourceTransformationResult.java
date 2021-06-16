package io.pulumi.resources;

public class ResourceTransformationResult {
    private ResourceArgs args;
    private ResourceOptions options;

    public ResourceTransformationResult(
        ResourceArgs args,
        ResourceOptions options
    ) {
        this.args = args;
        this.options = options;
    }

    public ResourceArgs getArgs() { return args; }
    public ResourceOptions getOptions() { return options; }
}

