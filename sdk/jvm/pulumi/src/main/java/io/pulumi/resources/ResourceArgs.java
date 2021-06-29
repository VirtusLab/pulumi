package io.pulumi.resources;

import java.lang.reflect.Type;

public abstract class ResourceArgs extends InputArgs {
    public static final ResourceArgs EMPTY = new ResourceArgs(){};

    protected void validateMember(Type memberType, String fullName) {};
}
