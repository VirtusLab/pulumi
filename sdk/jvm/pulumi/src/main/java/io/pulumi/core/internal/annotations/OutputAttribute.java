package io.pulumi.core.internal.annotations;

import io.pulumi.core.internal.Constants;

import javax.annotation.Nullable;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Attribute used by a mark @see {@link io.pulumi.resources.Resource} output properties.
 * Use this attribute in your Pulumi programs to mark outputs of @see {@link io.pulumi.resources.ComponentResource}
 * and @see {@link io.pulumi.Stack} resources.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface OutputAttribute {
    @Nullable
    String name();
}