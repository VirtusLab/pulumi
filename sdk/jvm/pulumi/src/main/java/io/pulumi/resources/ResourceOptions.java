package io.pulumi.resources;

import io.pulumi.core.Alias;
import io.pulumi.core.Input;
import io.pulumi.core.InputList;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static io.pulumi.resources.Resources.mergeNullableList;

/**
 * ResourceOptions is a bag of optional settings that control a resource's behavior.
 */
public abstract class ResourceOptions {
    @Nullable
    protected Input<String> id;
    @Nullable
    protected Resource parent;
    @Nullable
    protected InputList<Resource> dependsOn;
    protected boolean protect;
    @Nullable
    protected List<String> ignoreChanges;
    @Nullable
    protected String version;
    @Nullable
    protected ProviderResource provider;
    @Nullable
    protected CustomTimeouts customTimeouts;
    @Nullable
    protected List<ResourceTransformation> resourceTransformations;
    @Nullable
    protected List<Input<Alias>> aliases;
    @Nullable
    protected String urn;

    protected ResourceOptions() { /* empty */ }

    protected ResourceOptions(
            @Nullable Input<String> id,
            @Nullable Resource parent,
            @Nullable InputList<Resource> dependsOn,
            boolean protect,
            @Nullable List<String> ignoreChanges,
            @Nullable String version,
            @Nullable ProviderResource provider,
            @Nullable CustomTimeouts customTimeouts,
            @Nullable List<ResourceTransformation> resourceTransformations,
            @Nullable List<Input<Alias>> aliases,
            @Nullable String urn
    ) {
        this.id = id;
        this.parent = parent;
        this.dependsOn = dependsOn;
        this.protect = protect;
        this.ignoreChanges = ignoreChanges;
        this.version = version;
        this.provider = provider;
        this.customTimeouts = customTimeouts;
        this.resourceTransformations = resourceTransformations;
        this.aliases = aliases;
        this.urn = urn;
    }

    /**
     * An optional existing ID to load, rather than create.
     */
    public Optional<Input<String>> getId() {
        return Optional.ofNullable(id);
    }

    /**
     * An optional parent resource to which this resource belongs.
     */
    public Optional<Resource> getParent() {
        return Optional.ofNullable(parent);
    }

    /**
     * Optional additional explicit dependencies on other resources.
     */
    public InputList<Resource> getDependsOn() {
        return this.dependsOn == null ? new InputList<>() : this.dependsOn;
    }

    /**
     * When set to true, protect ensures this resource cannot be deleted.
     */
    public boolean getProtect() {
        return protect;
    }

    /**
     * Ignore changes to any of the specified properties.
     */
    public List<String> getIgnoreChanges() {
        return this.ignoreChanges == null ? List.of() : List.copyOf(this.ignoreChanges);
    }

    /**
     * An optional version, corresponding to the version of the provider plugin that should be
     * used when operating on this resource. This version overrides the version information
     * inferred from the current package and should rarely be used.
     */
    public Optional<String> getVersion() {
        return Optional.ofNullable(version);
    }

    /**
     * An optional provider to use for this resource's CRUD operations. If no provider is
     * supplied, the default provider for the resource's package will be used. The default
     * provider is pulled from the parent's provider bag
     * (see also ComponentResourceOptions.providers).
     * <p>
     * If this is a @see {@link ComponentResourceOptions} do not provide both @see {@link #provider}
     * and @see {@link ComponentResourceOptions#getProviders()}.
     */
    public Optional<ProviderResource> getProvider() {
        return Optional.ofNullable(provider);
    }

    /**
     * An optional CustomTimeouts configuration block.
     */
    public Optional<CustomTimeouts> getCustomTimeouts() {
        return Optional.ofNullable(customTimeouts);
    }

    /**
     * Optional list of transformations to apply to this resource during construction.
     * The transformations are applied in order, and are applied prior to transformation applied to
     * parents walking from the resource up to the stack.
     */
    public List<ResourceTransformation> getResourceTransformations() {
        return this.resourceTransformations == null ? List.of() : List.copyOf(this.resourceTransformations);
    }

    /**
     * An optional list of aliases to treat this resource as matching.
     */
    public List<Input<Alias>> getAliases() {
        return this.aliases == null ? List.of() : List.copyOf(this.aliases);
    }

    /**
     * The URN of a previously-registered resource of this type to read from the engine.
     */
    public Optional<String> getUrn() {
        return Optional.ofNullable(urn);
    }

    protected static <T extends ResourceOptions> T mergeSharedOptions(T options1, T options2) {
        Objects.requireNonNull(options1);
        Objects.requireNonNull(options2);

        options1.id = options2.id == null ? options1.id : options2.id;
        options1.parent = options2.parent == null ? options1.parent : options2.parent;
        options1.protect = options1.protect || options2.protect;
        options1.urn = options2.urn == null ? options1.urn : options2.urn;
        options1.version = options2.version == null ? options1.version : options2.version;
        options1.provider = options2.provider == null ? options1.provider : options2.provider;
        options1.customTimeouts = options2.customTimeouts == null ? options1.customTimeouts : options2.customTimeouts;

        options1.ignoreChanges = mergeNullableList(options1.ignoreChanges, options2.ignoreChanges);
        options1.resourceTransformations = mergeNullableList(options1.resourceTransformations, options2.resourceTransformations);
        options1.aliases = mergeNullableList(options1.aliases, options2.aliases);

        options1.dependsOn = options1.getDependsOn().concat(options2.getDependsOn());

        return options1;
    }
}
