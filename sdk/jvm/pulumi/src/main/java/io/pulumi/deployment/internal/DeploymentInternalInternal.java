package io.pulumi.deployment.internal;

import io.pulumi.Stack;
import io.pulumi.core.Output;
import io.pulumi.resources.Resource;
import io.pulumi.resources.ResourceArgs;
import io.pulumi.resources.ResourceOptions;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public interface DeploymentInternalInternal extends DeploymentInternal {
    Optional<String> getConfig(String fullKey);

    boolean isConfigSecret(String fullKey);

    Stack getStack();

    void setStack(Stack stack);

    EngineLogger getLogger();

    Runner getRunner();

    void readOrRegisterResource(Resource resource, boolean remote, Function<String, Resource> newDependency,
                                ResourceArgs args, ResourceOptions opts);

    void registerResourceOutputs(Resource resource, Output<Map<String, Optional<Object>>> outputs);
}
