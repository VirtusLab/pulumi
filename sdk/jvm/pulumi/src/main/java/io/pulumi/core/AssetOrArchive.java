package io.pulumi.core;

import io.grpc.Internal;

import java.util.Objects;

/**
 * Base class of @see {@link Asset}s and @see {@link Archive}s.
 */
public abstract class AssetOrArchive {
    protected final String sigKey;
    protected final String propName;
    protected final Object value;

    protected AssetOrArchive(String sigKey, String propName, Object value) {
        this.sigKey = Objects.requireNonNull(sigKey);
        this.propName = Objects.requireNonNull(propName);
        this.value = Objects.requireNonNull(value);
    }

    @Internal
    String getSigKey() {
        return sigKey;
    }

    @Internal
    Object getValue() {
        return value;
    }

    @Internal
    public String getPropName() {
        return propName;
    }
}
