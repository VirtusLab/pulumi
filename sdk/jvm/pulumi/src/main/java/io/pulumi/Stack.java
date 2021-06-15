package io.pulumi;

import io.pulumi.deployment.Deployment;
import io.pulumi.resources.ComponentResource;

import javax.annotation.Nullable;

public class Stack extends ComponentResource {

    private static final String rootPulumiStackTypeName = "pulumi:pulumi:Stack";

    public Stack(@Nullable StackOptions options) {
        super(
            rootPulumiStackTypeName,
            String.format("%s-%s", Deployment.INSTANCE.getProjectName(), Deployment.INSTANCE.getStackName()),
            convertOptions(options)
        );
        // TODO: wut?!
        // Deployment.InternalInstance.Stack = this;
    }

    private static @Nullable ComponentResourceOptions convertOptions(@Nullable StackOptions options)
    {
        if (options == null)
            return null;

        return new ComponentResourceOptions(){
            this.resourceTransformations = options.ResourceTransformations;
        };
    }
}
