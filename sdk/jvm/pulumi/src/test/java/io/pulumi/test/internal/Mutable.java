package io.pulumi.test.internal;

import com.google.common.base.MoreObjects;

import java.util.Objects;

public class Mutable<T> {
    public T value;

    public Mutable(T value) {
        this.value = value;
    }

    public static <T> Mutable<T> of(T value) {
        return new Mutable<>(value);
    }

    public static <T> Mutable<T> aNull() {
        return new Mutable<>(null);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("value", value)
                .toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Mutable<?> mutable = (Mutable<?>) o;
        return Objects.equals(value, mutable.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }
}
