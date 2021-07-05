package io.pulumi.core;

import io.pulumi.core.Input;
import io.pulumi.deployment.Deployment;
import io.pulumi.resources.Resource;
import io.pulumi.resources.Resources;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.pulumi.core.internal.Objects.requireNullState;

/**
 * Alias is a description of prior named used for a resource. It can be processed in the
 * context of a resource creation to determine what the full aliased URN would be.
 *
 * Use @see {@link Urn} in the case where a prior URN is known and can just be specified in
 * full.  Otherwise, provide some subset of the other properties in this type to generate an
 * appropriate urn from the pre-existing values of the @see {@link io.pulumi.resources.Resource}
 * with certain parts overridden.
 *
 * The presence of a property indicates if its value should be used. If absent (i.e. "null"), then the value is not used.
 *
 * Note: because of the above, there needs to be special handling to indicate that the previous
 * "parent" of a @see {@link io.pulumi.resources.Resource} was "null".
 * Specifically, pass in: Alias.noParent()
 */
public class Alias {

    @Nullable
    private final String urn;
    @Nullable
    private final io.pulumi.core.Input<String> name;
    @Nullable
    private final io.pulumi.core.Input<String> type;
    @Nullable
    private final io.pulumi.core.Input<String> stack;
    @Nullable
    private final io.pulumi.core.Input<String> project;
    @Nullable
    private final Resource parent;
    @Nullable
    private final io.pulumi.core.Input<String> parentUrn;
    private final boolean noParent;

    @SuppressWarnings("unused")
    private Alias() {
        throw new UnsupportedOperationException();
    }

    private Alias(
            @Nullable String urn,
            @Nullable Input<String> name,
            @Nullable Input<String> type,
            @Nullable Input<String> stack,
            @Nullable Input<String> project,
            @Nullable Resource parent,
            @Nullable Input<String> parentUrn,
            boolean noParent
    ) {
        this.urn = urn;
        this.name = name;
        this.type = type;
        this.stack = stack;
        this.project = project;
        this.parent = parent;
        this.parentUrn = parentUrn;
        this.noParent = noParent;
    }

    public static Alias noParent() {
        return new Alias(
                null, // TODO what about all those values???
                null,
                null,
                null,
                null,
                null,
                null,
                true
        );
    }

    public static Alias create(String urn) {
        return new Alias(
                Objects.requireNonNull(urn),
                null,
                null,
                null,
                null,
                null,
                null,
                false
        );
    }

    public static Alias create(
            Input<String> name,
            Input<String> type,
            Input<String> stack,
            Input<String> project,
            Input<String> parentUrn
    ) {
        return new Alias(
                null,
                Objects.requireNonNull(name),
                Objects.requireNonNull(type),
                Objects.requireNonNull(stack),
                Objects.requireNonNull(project),
                null,
                Objects.requireNonNull(parentUrn),
                false
        );
    }

    public static Alias create(
            Input<String> name,
            Input<String> type,
            Input<String> stack,
            Input<String> project,
            Resource parent
    ) {
        return new Alias(
                null,
                Objects.requireNonNull(name),
                Objects.requireNonNull(type),
                Objects.requireNonNull(stack),
                Objects.requireNonNull(project),
                Objects.requireNonNull(parent),
                null,
                false
        );
    }

    /**
     * The previous urn to alias to. If this is provided, no other properties in this type should be provided.
     */
    public Optional<String> getUrn() {
        // TODO: we cloud probably move this check to regression tests
        if (this.urn != null) {
            Function<String, Supplier<String>> conflict = (String field) ->
                    () -> String.format("Alias should not specify both Alias#urn and Alias#%s", field);
            requireNullState(name, conflict.apply("name"));
            requireNullState(type, conflict.apply("type"));
            requireNullState(project, conflict.apply("project"));
            requireNullState(stack, conflict.apply("stack"));
            requireNullState(parent, conflict.apply("parent"));
            requireNullState(parentUrn, conflict.apply("parentUrn"));
            if (noParent)
                throw new IllegalStateException(conflict.apply("noParent").get());
        }
        return Optional.ofNullable(this.urn);
    }

    /**
     * The previous name of the resource. If empty, the current name of the resource is used.
     */
    public Optional<Input<String>> getName() {
        return Optional.ofNullable(name);
    }

    /**
     * The previous type of the resource. If empty, the current type of the resource is used.
     */
    public Optional<Input<String>> getType() {
        return Optional.ofNullable(type);
    }

    /**
     * The previous stack of the resource. If empty, defaults to
     * the value of @see {@link Deployment#getStackName()}
     */
    public Optional<Input<String>> getStack() {
        return Optional.ofNullable(stack);
    }

    /**
     * The previous project of the resource. if empty, defaults to the value
     * of @see {@link Deployment#getProjectName()}
     */
    public Optional<Input<String>> getProject() {
        return Optional.ofNullable(project);
    }

    /**
     * The previous parent of the resource. If empty, the current parent of the resource is used.
     *
     * To specify no original parent, use "noParent".
     *
     * Only specify one of "parent" or "parentUrn" or "noParent".
     */
    public Optional<Resource> getParent() {
        return Optional.ofNullable(parent);
    }

    /**
     * The previous parent of the resource. if empty, the current parent of
     * the resource is used.
     *
     * To specify no original parent, use "noParent".
     *
     * Only specify one of "parent" or "parentUrn" or "noParent".
     */
    public Optional<Input<String>> getParentUrn() {
        return Optional.ofNullable(parentUrn);
    }

    /**
     * Used to indicate the resource previously had no parent.  If "false" this property is ignored.
     *
     * Only specify one of "parent" or "parentUrn" or "noParent".
     */
    public boolean hasNoParent() {
        return noParent;
    }
}