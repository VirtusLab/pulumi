package io.pulumi.resources;

import java.lang.reflect.Type;

/**
 * Base type for all resource argument classes.
 */
public abstract class ResourceArgs extends InputArgs {
    public static final ResourceArgs Empty = new ResourceArgs(){};

    @Override
    protected void validateMember(Class<?> memberType, String fullName) {
        // No validation. A member may or may not be IInput.
    }
}
