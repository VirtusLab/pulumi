package io.pulumi.core.internal.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Attribute used by a Pulumi Cloud Provider Package to mark complex types used for a Resource
 * output property. A complex type must have a single constructor in it marked with the
 * @see OutputConstructorAttribute attribute.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface OutputTypeAttribute {}
