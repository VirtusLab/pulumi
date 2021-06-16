package io.pulumi.resources;
import java.util.ArrayList;
import java.util.Optional;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import io.pulumi.core.Alias;
import io.pulumi.core.Input;
import io.pulumi.core.InputList;

public abstract class ResourceOptions {
    public Optional<Input<String>> Id;
    public Optional<Resource> parent;

    private Optional<InputList<Resource>> dependsOn;
    public InputList<Resource> getDependsOn() {
        if (!dependsOn.isEmpty()) {
            return dependsOn.get();
        } else {
            return new InputList<Resource>(); 
        }
    }
    public void setDependsOn(@Nullable InputList<Resource> list) {
        if (list != null) {
            this.dependsOn = Optional.of(list);
        } else {
            this.dependsOn = Optional.empty();
        }
    }

    public Optional<Boolean> protect;
    
    private Optional<ArrayList<String>> ignoreChanges;
    public ArrayList<String> getIgnoreChanges() {
        if (!ignoreChanges.isEmpty()) {
            return ignoreChanges.get();
        } else {
            return new ArrayList<String>(); 
        }
    }
    public void setIgnoreChanges(@Nullable ArrayList<String> list) {
        if (list != null) {
            this.ignoreChanges = Optional.of(list);
        } else {
            this.ignoreChanges = Optional.empty();
        }
    }

    public Optional<String> version;
    public Optional<ProviderResource> provider;
    public Optional<CustomTimeout> customTimeouts;
    private Optional<ImmutableList<ResourceTransformation>> resourceTransformations;
    public ImmutableList<ResourceTransformation> getResourceTransformations() {
        if (!resourceTransformations.isEmpty()) {
            return resourceTransformations.get();
        } else {
            return ImmutableList.of();
        }
    }
    public void setResourceTransformation(@Nullable ImmutableList<ResourceTransformation> list) {
        if (list != null) {
            this.resourceTransformations = Optional.of(list);
        } else {
            this.resourceTransformations = Optional.empty();
        }
    }
    public ArrayList<Input<Alias>> aliases;
    public Optional<String> urn;

    protected abstract ResourceOptions clone();

    protected void copyValues(ResourceOptions from) {
        this.aliases = new ArrayList<Input<Alias>>(from.aliases);
        this.customTimeouts = from.customTimeouts.map(c -> c.clone());
        this.dependsOn = from.dependsOn.map(d -> d.clone());
        this.Id = from.Id;
        this.parent = from.parent;
        this.ignoreChanges = from.ignoreChanges.map(c -> new ArrayList<String>(c));
        this.protect = from.protect;
        this.provider = from.provider;
        this.resourceTransformations = from.resourceTransformations.map(t -> ImmutableList.copyOf(t));
        this.urn = from.urn;
        this.version = from.version;
    }
}
