package io.pulumi.core;

import java.util.Objects;

public abstract class AssetOrArchive {
    final public String sigKey;
    final public String propName;
    final public Object value;

    protected AssetOrArchive(String sigKey, String propName, Object value) {
        Objects.requireNonNull(sigKey);
        Objects.requireNonNull(propName);
        Objects.requireNonNull(value);

        this.sigKey = sigKey;
        this.propName = propName;
        this.value = value;
    }
}
