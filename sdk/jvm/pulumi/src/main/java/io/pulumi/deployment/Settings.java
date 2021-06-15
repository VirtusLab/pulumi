package io.pulumi.deployment;


import javax.annotation.Nonnull;

public interface Settings {
    /**
     * @return the current stack name
     */
    @Nonnull
    String getStackName();

    /**
     * @return the current project name
     */
    @Nonnull
    String getProjectName();

    /**
     * Whether or not the application is currently being previewed or actually applied.
     * @return true if application is being applied
     */
    boolean isDryRun();
}
