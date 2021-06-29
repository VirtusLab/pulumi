package io.pulumi.deployment;

import io.pulumi.core.Environment;

import javax.annotation.Nonnull;

import static java.lang.Boolean.parseBoolean;

public enum Deployment implements Settings {
    INSTANCE(fromEnvironment());

    private final DeploymentState state;

    private static class DeploymentState {
        public final String projectName;
        public final String stackName;
        public final boolean isDryRun;
        public final Engine engine;
        public final Monitor monitor;
        public final Runner runner;
        public final Logger logger;

        private DeploymentState(
                String projectName,
                String stackName,
                boolean isDryRun,
                Engine engine,
                Monitor monitor,
                Runner runner,
                Logger logger) {
            this.projectName = projectName;
            this.stackName = stackName;
            this.isDryRun = isDryRun;
            this.engine = engine;
            this.monitor = monitor;
            this.runner = runner;
            this.logger = logger;
        }
    }

    Deployment(DeploymentState state) {
        this.state = state;
    }

    /**
     * @throws IllegalArgumentException if an environment variable is not found
     */
    public static DeploymentState fromEnvironment() {
        try {
            var monitorTarget = Environment.getEnvironmentVariable("PULUMI_MONITOR");
            var engineTarget = Environment.getEnvironmentVariable("PULUMI_ENGINE");
            var project = Environment.getEnvironmentVariable("PULUMI_PROJECT");
            var stack = Environment.getEnvironmentVariable("PULUMI_STACK");
            var pwd = Environment.getEnvironmentVariable("PULUMI_PWD");
            var dryRun = Environment.getEnvironmentVariable("PULUMI_DRY_RUN");
            var queryMode = Environment.getEnvironmentVariable("PULUMI_QUERY_MODE");
            var parallel = Environment.getEnvironmentVariable("PULUMI_PARALLEL");
            var tracing = Environment.getEnvironmentVariable("PULUMI_TRACING");
            // TODO what to do with all the unused envvars?

            // TODO init logging

            var engine = new GrpcEngine(engineTarget);
            var monitor = new GrpcMonitor(monitorTarget);
            var runner = new GuavaExecutor();
            var logger = new Logger(){
                
            };

            return new DeploymentState(project, stack,
                    parseBoolean(dryRun), // TODO make it safer with Environment.getBooleanEnvironmentVariable, don't want truthy
                    engine,
                    monitor,
                    runner,
                    logger
            );
        } catch (NullPointerException ex) {
            throw new IllegalArgumentException(
                    "Program run without the Pulumi engine available; re-run using the `pulumi` CLI", ex);
        }
    }

    @Override
    @Nonnull
    public String getStackName() {
        return state.stackName;
    }

    @Override
    @Nonnull
    public String getProjectName() {
        return state.projectName;
    }

    @Override
    public boolean isDryRun() {
        return state.isDryRun;
    }

    public Logger getLogger() {
        return state.logger;
    }
}
