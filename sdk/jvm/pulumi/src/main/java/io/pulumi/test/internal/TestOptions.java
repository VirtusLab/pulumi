package io.pulumi.test.internal;

import javax.annotation.Nullable;

/**
 * Optional settings for @see {@link io.pulumi.deployment.Deployment#testAsync}.
 */
public class TestOptions {
    private final String projectName;
    private final String stackName;
    private final boolean preview;

    public TestOptions() {
        this(null, null, true);
    }

    public TestOptions(@Nullable String projectName, @Nullable String stackName) {
        this(projectName, stackName, true);
    }

    public TestOptions(@Nullable String projectName, @Nullable String stackName, boolean preview) {
        this.projectName = projectName != null ? projectName : "project";
        this.stackName = stackName != null ? stackName : "stack";
        this.preview = preview;
    }

    /**
     * Project name. Defaults to <b>"project"</b> if not specified.
     */
    public String getProjectName() {
        return projectName;
    }

    /**
     * Stack name. Defaults to <b>"stack"</b> if not specified.
     */
    public String getStackName() {
        return stackName;
    }

    /**
     * Whether the test runs in Preview mode. Defaults to <b>true</b> if not specified.
     */
    public boolean isPreview() {
        return preview;
    }
}
