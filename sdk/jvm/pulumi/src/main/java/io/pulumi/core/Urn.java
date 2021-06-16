package io.pulumi.core;

import java.util.Optional;

import io.pulumi.deployment.Deployment;
import io.pulumi.resources.Resource;

public class Urn {
    public static Output<Alias> inheritedChildAlias(String childName, String parentName, Input<String> parentAlias, String childType) {
        Output<String> aliasName= Output.create(childName);
        if (childName.startsWith(parentName)) {
            aliasName = Output.create(parentAlias).apply(
                parentAliasUrn -> parentAliasUrn.substring(parentAliasUrn.lastIndexOf("::") + 2) + childName.substring(parentName.length())
                );
        }
        Output<String> urn = create(Input.create(aliasName), Input.create(childType), parentAlias, Optional.empty(), Optional.empty());
        return urn.apply(u -> Alias.fromUrn(u));
    }
    public static Output<String> create(Input<String> name, Input<String> type, Optional<Input<String>> project, Optional<Input<String>> stack) {
        Output<String> outputStack = stack.isPresent() ? Output.create(stack.get()) : Output.create(Deployment.INSTANCE.getStackName());
        Output<String> outputProject = project.isPresent() ? Output.create(project.get()) : Output.create(Deployment.INSTANCE.getProjectName());
        Output<String> parentPrefix = Output.format("urn:pulumi:%s::%s::", Input.create(outputStack), Input.create(outputProject));
        return Output.format("%s%s::%s", Input.create(parentPrefix), type, name);
    }
    public static Output<String> create(Input<String> name, Input<String> type, Input<String> parentUrn, Optional<Input<String>> project, Optional<Input<String>> stack) {
        Output<String> parentUrnOutput = Output.create(parentUrn);
        Output<String> parentPrefix = parentUrnOutput.apply(
            parentUrnString -> getParentPrefix(parentUrnString)
        );

        return Output.format("%s%s::%s", Input.create(parentPrefix), type, name);
    }
    public static Output<String> create(Input<String> name, Input<String> type, Resource parent, Optional<Input<String>> project, Optional<Input<String>> stack) {
        Output<String> parentUrnOutput = parent.urn;
        Output<String> parentPrefix = parentUrnOutput.apply(
            parentUrnString -> getParentPrefix(parentUrnString)
        );

        return Output.format("%s%s::%s", Input.create(parentPrefix), type, name);
    }
    private static String getParentPrefix(String parentUrn) {
        return parentUrn.substring(0, parentUrn.lastIndexOf("::")) + "$";
    }
}
