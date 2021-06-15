package io.pulumi.core.internal.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Attribute used by a Pulumi Cloud Provider Package to marks the constructor for a complex
 * property type so that it can be instantiated by the Pulumi runtime.
 * <p/>
 * The constructor should contain parameters that map to
 * the resultant struct returned by the engine.
 */ // TODO: clarify the javadoc, the C# implementation references Struct.Fields that is somehow related to JSON
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.CONSTRUCTOR)
public @interface OutputConstructorAttribute {}
