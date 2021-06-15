package io.pulumi.core;

import com.google.common.base.Strings;

import java.util.Objects;

public class Environment {

    private Environment() {
        throw new UnsupportedOperationException();
    }

    public static String getEnvironmentVariable(String name)  {
        return Objects.requireNonNull(
                Strings.emptyToNull(System.getenv(name)),
                String.format("expected environment variable '%s', not found", name)
        );
    }
}
