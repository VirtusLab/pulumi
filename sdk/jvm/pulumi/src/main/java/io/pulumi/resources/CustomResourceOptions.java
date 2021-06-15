package io.pulumi.resources;

import io.pulumi.core.Alias;
import io.pulumi.core.Input;
import io.pulumi.core.InputList;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;

import static io.pulumi.resources.Resources.copyNullableList;
import static io.pulumi.resources.Resources.mergeNullableList;

/**
 * A bag of optional settings that control a @see {@link ComponentResource} behavior.
 */
public final class CustomResourceOptions extends ResourceOptions {
    public static final CustomResourceOptions EMPTY = new CustomResourceOptions();

    private boolean deleteBeforeReplace;
    @Nullable
    private List<String> additionalSecretOutputs;
    @Nullable
    private String importId;

    protected CustomResourceOptions() { /* empty */ }

    public CustomResourceOptions(
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
            @Nullable String urn,
            boolean deleteBeforeReplace,
            @Nullable List<String> additionalSecretOutputs,
            @Nullable String importId
    ) {
        super(id, parent, dependsOn, protect, ignoreChanges, version, provider, customTimeouts,
                resourceTransformations, aliases, urn);
        this.deleteBeforeReplace = deleteBeforeReplace;
        this.additionalSecretOutputs = additionalSecretOutputs;
        this.importId = importId;
    }

    /**
     * When set to "true", indicates that this resource should be deleted before its
     * replacement is created when replacement is necessary.
     */
    public boolean getDeleteBeforeReplace() {
        return this.deleteBeforeReplace;
    }

    /**
     * The names of outputs for this resource that should be treated as secrets. This augments
     * the list that the resource provider and pulumi engine already determine based on inputs
     * to your resource. It can be used to mark certain outputs as a secrets on a per resource
     * basis.
     */
    public List<String> getAdditionalSecretOutputs() {
        return this.additionalSecretOutputs == null ? List.of() : List.copyOf(this.additionalSecretOutputs);
    }

    /**
     * When provided with a resource ID, import indicates that this resource's provider should
     * import its state from the cloud resource with the given ID.The inputs to the resource's
     * constructor must align with the resource's current state.Once a resource has been
     * imported, the import property must be removed from the resource's options.
     */
    public Optional<String> getImportId() {
        return Optional.ofNullable(importId);
    }

    public CustomResourceOptions copy() {
        return copy(this);
    }

    public static CustomResourceOptions copy(@Nullable CustomResourceOptions options) {
        if (options == null) {
            return EMPTY;
        }

        return new CustomResourceOptions(
                options.id,
                options.parent,
                options.dependsOn.copy(),
                options.protect,
                copyNullableList(options.ignoreChanges),
                options.version,
                options.provider,
                options.getCustomTimeouts().map(CustomTimeouts::copy).orElse(null),
                copyNullableList(options.resourceTransformations),
                copyNullableList(options.aliases),
                options.urn,
                options.deleteBeforeReplace,
                copyNullableList(options.additionalSecretOutputs),
                options.importId
        );
    }

    /**
     * Takes two @see {@link CustomResourceOptions} values and produces a new @see {@link CustomResourceOptions}
     * with the respective properties of "options2" merged over the same properties in "options1".
     * <p>
     * The original options objects will be unchanged.
     * A new instance will always be returned.
     * <p>
     * Conceptually property merging follows these basic rules:
     * - If the property is a collection, the final value will be a collection containing the
     * values from each options object.
     * - Simple scalar values from "options2" (i.e. "string", "int", "bool") will replace the values of "options1".
     * - "null" values in "options2" will be ignored.
     */
    public static CustomResourceOptions merge(
            @Nullable CustomResourceOptions options1,
            @Nullable CustomResourceOptions options2
    ) {
        options1 = options1 != null ? copy(options1) : EMPTY;
        options2 = options2 != null ? copy(options2) : EMPTY;

        // first, merge all the normal option values over
        mergeNormalOptions(options1, options2);

        options1.deleteBeforeReplace = options2.deleteBeforeReplace; // TODO: is this correct, do we need more logic?
        options1.importId = options2.importId == null ? options1.importId : options2.importId;

        options1.additionalSecretOutputs = mergeNullableList(options1.additionalSecretOutputs, options2.additionalSecretOutputs);
        return options1;
    }
}
