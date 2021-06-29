package io.pulumi.resources;

public class ResourceTransformationArgs {
    private Resource resource;
    private ResourceArgs args;
    private ResourceOptions options;

    public ResourceTransformationArgs(
        Resource resource,
        ResourceArgs args,
        ResourceOptions options
    ) {
        this.resource = resource;
        this.args = args;
        this.options = options;
    }

    public Resource getResource() { return resource; }
    public ResourceArgs getArgs() { return args; }
    public ResourceOptions getOptions() { return options; }
}

