package io.pulumi.core.internal;

public interface Copyable<C extends Copyable<C>> {
    C copy();
}
