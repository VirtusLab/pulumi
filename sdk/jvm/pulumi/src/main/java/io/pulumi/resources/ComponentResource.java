package io.pulumi.resources;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;

import io.pulumi.core.Output;
import io.pulumi.deployment.Deployment;

public class ComponentResource extends Resource {
    public ComponentResource(String type, String name, ComponentResourceOptions options) {
        this(type, name, ResourceArgs.EMPTY, options, false);
    }

    public ComponentResource(String type, String name, ComponentResourceOptions options, boolean remote) {
        this(type, name, ResourceArgs.EMPTY, options, remote);
    }

    public ComponentResource(String type, String name, @Nullable ResourceArgs args, @Nullable ComponentResourceOptions options, boolean remote) {
        super(type, name, false, args == null ? ResourceArgs.EMPTY : args, options == null ? new ComponentResourceOptions() : options, remote, false);
    }

    private void registerOutputs() {
        registerOutputs(ImmutableMap.<String, Object>of());
    }
    private void registerOutputs(Map<String, Object> outputs) {
        Objects.requireNonNull(outputs);
        registerOutputs(CompletableFuture.completedFuture(outputs));
    }
    private void registerOutputs(CompletableFuture<Map<String, Object>> outputs) {
        Objects.requireNonNull(outputs);
        registerOutputs(Output.create(outputs));
    }
    private void registerOutputs(Output<Map<String, Object>> outputs) {
        Objects.requireNonNull(outputs);
        Deployment.INSTANCE.registerResourceOutputs(this, outputs);
    }
}
