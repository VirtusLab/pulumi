package io.pulumi.core.internal.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Attribute used by a Pulumi Cloud Provider Package to mark enum types.
 *
 * Requirements for a struct-based enum to be (de)serialized are as follows.
 * It must:
 *   * Be a value type (struct) decorated with EnumTypeAttribute.
 *   * Have a constructor that takes a single parameter of the underlying type.
 *     The constructor can be private.
 *   * Have an explicit conversion operator that converts the enum type to the underlying type.
 *   * Have an underlying type of String or Double.
 *   * Implementing IEquatable, adding ==/=! operators and overriding ToString isn't required,
 *     but is recommended and is what our codegen does.
 */
@Retention(RetentionPolicy.RUNTIME)
// [AttributeUsage(AttributeTargets.Struct)] how are we going to do structs in Java??
//@Target(???) TODO
public @interface EnumTypeAttribute {}
