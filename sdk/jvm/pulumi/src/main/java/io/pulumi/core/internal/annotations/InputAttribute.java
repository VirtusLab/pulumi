package io.pulumi.core.internal.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Attribute used by a Pulumi Cloud Provider Package
 * to mark @see {@link io.pulumi.resources.Resource} input fields.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface InputAttribute {
    String name();

    boolean isRequired() default false;

    boolean json() default false;
}
