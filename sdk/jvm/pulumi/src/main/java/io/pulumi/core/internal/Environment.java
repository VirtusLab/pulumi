package io.pulumi.core.internal;

import com.google.common.base.Strings;
import io.grpc.Internal;

import java.util.Objects;

@Internal
public class Environment {

    private Environment() {
        throw new UnsupportedOperationException();
    }

    public static String getEnvironmentVariable(String name) {
        Objects.requireNonNull(name);
        var value = System.getenv(name).trim();
        return Objects.requireNonNull(
                Strings.emptyToNull(value),
                String.format("expected environment variable '%s', not found or empty", name)
        );
    }

    public static boolean getBooleanEnvironmentVariable(String name) {
        var value = Environment.getEnvironmentVariable(name);
        return Objects.equals(value, "1") || Boolean.parseBoolean(value);
    }
}
