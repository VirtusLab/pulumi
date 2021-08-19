package io.pulumi.deployment.internal;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.grpc.Internal;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

@ParametersAreNonnullByDefault
@Internal
class InlineDeploymentSettings {

    @Nullable
    private final Logger logger;

    private final String engineAddr;

    private final String monitorAddr;

    private final ImmutableMap<String, String> config;

    private final ImmutableSet<String> configSecretKeys;

    private final String project;

    private final String stack;

    private final int parallel;

    private final boolean isDryRun;

    public InlineDeploymentSettings(
            @Nullable Logger logger,
            String engineAddr,
            String monitorAddr,
            ImmutableMap<String, String> config,
            ImmutableSet<String> configSecretKeys,
            String project,
            String stack,
            int parallel,
            boolean isDryRun
    ) {
        this.logger = logger;
        this.engineAddr = Objects.requireNonNull(engineAddr);
        this.monitorAddr = Objects.requireNonNull(monitorAddr);
        this.config = Objects.requireNonNull(config);
        this.configSecretKeys = Objects.requireNonNull(configSecretKeys);
        this.project = Objects.requireNonNull(project);
        this.stack = Objects.requireNonNull(stack);
        this.parallel = parallel;
        this.isDryRun = isDryRun;
    }

    public Optional<Logger> getLogger() {
        return Optional.ofNullable(this.logger);
    }

    public String getEngineAddr() {
        return this.engineAddr;
    }

    public String getMonitorAddr() {
        return this.monitorAddr;
    }

    public ImmutableMap<String, String> getConfig() {
        return config;
    }

    public ImmutableSet<String> getConfigSecretKeys() {
        return this.configSecretKeys;
    }

    public String getProject() {
        return this.project;
    }

    public String getStack() {
        return this.stack;
    }

    public int getParallel() {
        return this.parallel;
    }

    public boolean isDryRun() {
        return this.isDryRun;
    }
}
