package io.pulumi;

import io.pulumi.deployment.Deployment;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class Config {
    private final String name;

    public Config(@Nullable String name) {
        if (name == null) {
            name = Deployment.getInstance().getProjectName();
        }

        if (name.endsWith(":config")) {
            name = name.replaceAll(":config$", "");
        }

        this.name = name;
    }

    @Nonnull
    public String getName() {
        return name;
    }

}