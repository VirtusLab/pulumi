package io.pulumi.serialization;

import com.google.common.collect.ImmutableSet;
import io.pulumi.resources.Resource;

import java.util.Map.Entry;

import static java.util.Map.entry;

public class OutputData<T> {
    protected final ImmutableSet<Resource> resources;
    protected final T value;
    protected final boolean known;
    protected final boolean secret;

    public OutputData(ImmutableSet<Resource> resources, T value, boolean isKnown, boolean isSecret)
    {
        this.resources = resources;
        this.value = value;
        this.known = isKnown;
        this.secret = isSecret;
    }

    public static <T> OutputData<T> create(ImmutableSet<Resource> resources, T value, boolean isKnown, boolean isSecret) {
        return new OutputData<>(resources, value, isKnown, isSecret);
    }

    public static <T> Entry<Boolean, Boolean> combine(OutputData<T> data, boolean isKnown, boolean isSecret) {
        return entry(isKnown && data.isKnown(), isSecret || data.isSecret());
    }

    // TODO

    public ImmutableSet<Resource> getResources() {
        return resources;
    }

    public T getValue() {
        return value;
    }

    public boolean isKnown() {
        return known;
    }

    public boolean isSecret() {
        return secret;
    }
}
