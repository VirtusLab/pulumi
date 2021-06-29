package io.pulumi.resources;

import java.util.function.Function;

import javax.annotation.Nullable;

public class ResourceTransformation {
    private Function<ResourceTransformationArgs, ResourceTransformationResult> function;
    private ResourceTransformation(Function<ResourceTransformationArgs, ResourceTransformationResult> fun) {
        this.function = fun;
    }
    public ResourceTransformation f(Function<ResourceTransformationArgs, ResourceTransformationResult> fun) {
        return new ResourceTransformation(fun);
    }
    protected @Nullable ResourceTransformationResult apply(ResourceTransformationArgs args) {
        return function.apply(args);
    }
}
