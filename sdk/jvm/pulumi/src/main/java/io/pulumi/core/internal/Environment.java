package io.pulumi.core.internal;

import io.grpc.Internal;

import java.util.Objects;
import java.util.Optional;

@Internal
public class Environment {

    private Environment() {
        throw new UnsupportedOperationException();
    }

    public static String requireEnvironmentVariable(String name) {
        return getEnvironmentVariable(name).orElseThrow(() ->
                new IllegalArgumentException(String.format(
                        "expected environment variable '%s', not found or empty", name)
                ));
    }

    public static Optional<String> getEnvironmentVariable(String name) {
        Objects.requireNonNull(name);
        return Optional.ofNullable(System.getenv(name))
                .map(String::trim) // make sure we get rid of white spaces
                .map(v -> v.isEmpty() ? null : v); // make sure we return empty string as empty optional
    }

    public static boolean requireBooleanEnvironmentVariable(String name) {
        return getBooleanEnvironmentVariable(name).orElseThrow(() ->
                new IllegalArgumentException(String.format(
                        "expected environment variable '%s', not found or empty", name)
                ));
    }

    public static Optional<Boolean> getBooleanEnvironmentVariable(String name) {
        return getEnvironmentVariable(name)
                .map(value -> Objects.equals(value, "1") || Boolean.parseBoolean(value));
    }
}
