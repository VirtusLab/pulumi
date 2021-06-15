package io.pulumi.resources;

import io.pulumi.core.Alias;
import io.pulumi.core.Input;
import io.pulumi.core.InputList;

import javax.annotation.Nullable;
import java.util.List;

import static io.pulumi.resources.Resources.copyNullableList;
import static io.pulumi.resources.Resources.mergeNullableList;

/**
 * A bag of optional settings that control a @see {@link ComponentResource} behavior.
 */
public final class ComponentResourceOptions extends ResourceOptions {
    public static final ComponentResourceOptions EMPTY = new ComponentResourceOptions();

    @Nullable
    private List<ProviderResource> providers;

    protected ComponentResourceOptions() { /* empty */ }

    public ComponentResourceOptions(
            @Nullable Input<String> id,
            @Nullable Resource parent,
            @Nullable InputList<Resource> dependsOn,
            boolean protect,
            @Nullable List<String> ignoreChanges,
            @Nullable String version,
            @Nullable CustomTimeouts customTimeouts,
            @Nullable List<ResourceTransformation> resourceTransformations,
            @Nullable List<Input<Alias>> aliases,
            @Nullable String urn,
            @Nullable List<ProviderResource> providers
    ) {
        super(id, parent, dependsOn, protect, ignoreChanges, version, null /* use providers instead */, customTimeouts,
                resourceTransformations, aliases, urn);
        this.providers = providers;
    }

    /**
     * An optional set of providers to use for child resources.
     * @return set of providers or empty
     */
    public List<ProviderResource> getProviders() {
        return providers == null ? List.of() : List.copyOf(providers);
    }

    public ComponentResourceOptions copy() {
        return copy(this);
    }

    public static ComponentResourceOptions copy(@Nullable ComponentResourceOptions options) {
        if (options == null) {
            return EMPTY;
        }

        return new ComponentResourceOptions(
                options.id,
                options.parent,
                options.getDependsOn().copy(),
                options.protect,
                copyNullableList(options.ignoreChanges),
                options.version,
                options.getCustomTimeouts().map(CustomTimeouts::copy).orElse(null),
                copyNullableList(options.resourceTransformations),
                copyNullableList(options.aliases),
                options.urn,
                copyNullableList(options.providers) // TODO: should we also invoke copy() on the items?
        );
    }

    /**
     * Takes two "ComponentResourceOptions" values and produces a new "ComponentResourceOptions"
     * with the respective properties of "options2" merged over the same properties in "options1".
     *
     * The original options objects will be unchanged.
     * A new instance will always be returned.
     *
     * Conceptually property merging follows these basic rules:
     * 1. If the property is a collection, the final value will be a collection containing the
     * values from each options object.
     * 2. Simple scalar values from "options2" (i.e. Strings, Integers, Booleans)
     * will replace the values of "options1".
     * 3. "null" values in "options2" will be ignored.
     */
    public static ComponentResourceOptions merge(
            @Nullable ComponentResourceOptions options1,
            @Nullable ComponentResourceOptions options2
    ) {
        options1 = options1 != null ? copy(options1) : EMPTY;
        options2 = options2 != null ? copy(options2) : EMPTY;

        if (options1.provider != null) {
            throw new IllegalStateException("unexpected non-null 'provider', should use only 'providers'");
        }
        if (options2.provider != null) {
            throw new IllegalStateException("unexpected non-null 'provider', should use only 'providers'");
        }

        // first, merge all the normal option values over
        mergeNormalOptions(options1, options2);

        options1.providers = mergeNullableList(options1.providers, options2.providers);

        return options1;
    }
}
