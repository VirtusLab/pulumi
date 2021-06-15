package io.pulumi.deployment;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import io.grpc.Internal;
import io.pulumi.Stack;
import io.pulumi.core.Output;
import io.pulumi.core.internal.CompletableFutures;
import io.pulumi.core.internal.Environment;
import io.pulumi.core.internal.Exceptions;
import io.pulumi.deployment.internal.*;
import io.pulumi.exceptions.LogException;
import io.pulumi.exceptions.ResourceException;
import io.pulumi.exceptions.RunException;
import io.pulumi.resources.*;
import io.pulumi.test.internal.TestOptions;
import pulumirpc.Resource.SupportsFeatureRequest;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.pulumi.core.internal.Environment.getBooleanEnvironmentVariable;
import static io.pulumi.core.internal.Environment.getEnvironmentVariable;
import static io.pulumi.core.internal.Exceptions.getStackTrace;

public class Deployment implements DeploymentInternalInternal {

    // TODO: maybe using a state machine for the uninitialized and initialized deployment would make sense
    //       not only it need the deployment instance, but also a stack - initialized after 'run' is called
    //       and config, ale probably more stuff...
    private static final AtomicReference<DeploymentInstance> instance = new AtomicReference<>();

    /**
     * The current running deployment instance. This is only available from inside the function
     * passed to @see {@link Deployments#runAsync(Supplier)} (or its overloads).
     *
     * @throws IllegalStateException if called before 'run' was called
     */
    public static DeploymentInstance getInstance() {
        if (instance.get() == null) {
            throw new IllegalStateException("Trying to acquire Deployment#getInstance before 'run' was called.");
        }
        return instance.get();
    }

    /**
     * @throws IllegalStateException if called more than once (the instance already set)
     */
    @Internal
    static void setInstance(@Nullable DeploymentInstance newInstance) {
        if (instance.get() != null) {
            throw new IllegalStateException("Deployment.getInstance should only be set once at the beginning of a 'run' call.");
        }
        instance.set(newInstance);
    }

    @Internal
    static DeploymentInternalInternal getInternalInstance() {
        return getInstance().getInternal();
    }

    private final DeploymentState state;

    protected Deployment() {
        this(fromEnvironment());
    }

    // TODO private Deployment(InlineDeploymentSettings settings)

    @Internal
    private Deployment(DeploymentState state) {
        this.state = state;
    }

    /**
     * @throws IllegalArgumentException if an environment variable is not found
     */
    private static DeploymentState fromEnvironment() {
        try {
            var disableResourceReferences = getBooleanEnvironmentVariable("PULUMI_DISABLE_RESOURCE_REFERENCES");
            var monitorTarget = getEnvironmentVariable("PULUMI_MONITOR");
            var engineTarget = getEnvironmentVariable("PULUMI_ENGINE");
            var project = getEnvironmentVariable("PULUMI_PROJECT");
            var stack = getEnvironmentVariable("PULUMI_STACK");
            var pwd = getEnvironmentVariable("PULUMI_PWD");
            var dryRun = getBooleanEnvironmentVariable("PULUMI_DRY_RUN");
            var queryMode = getBooleanEnvironmentVariable("PULUMI_QUERY_MODE");
            var parallel = getBooleanEnvironmentVariable("PULUMI_PARALLEL");
            var tracing = getEnvironmentVariable("PULUMI_TRACING");
            // TODO what to do with all the unused envvars?

            // FIXME: use different logger, not j.u.l!
            var standardLogger = Logger.getLogger(Deployment.class.getName());

            standardLogger.log(Level.FINEST, "Creating deployment engine");
            var engine = new GrpcEngine(engineTarget);
            standardLogger.log(Level.FINEST, "Created deployment engine");

            standardLogger.log(Level.FINEST, "Creating deployment monitor");
            var monitor = new GrpcMonitor(monitorTarget);
            standardLogger.log(Level.FINEST, "Created deployment monitor");

            var logger = new DefaultEngineLogger(standardLogger);
            var runner = new DefaultRunner(logger, standardLogger);

            return new DeploymentState(project, stack, dryRun, engine, monitor, runner, logger);
        } catch (NullPointerException ex) {
            throw new IllegalStateException(
                    "Program run without the Pulumi engine available; re-run using the `pulumi` CLI", ex);
        }
    }

    /**
     * @see #internalTestDeployment(Engine, Monitor, TestOptions) for details.
     */
    @VisibleForTesting
    @Internal
    static Deployment internalTestDeployment(Engine engine, Monitor monitor) {
        return internalTestDeployment( engine,  monitor, new TestOptions());
    }

    /**
     * This constructor is called from <see cref="TestAsync(IMocks, Func{IRunner, Task{int}}, TestOptions?)"/>
     * with a mocked monitor and dummy values for project and stack.
     * <para/>
     * This constructor is also used in deployment tests in order to
     * instantiate mock deployments.
     */
    @VisibleForTesting
    @Internal
    static Deployment internalTestDeployment(Engine engine, Monitor monitor, TestOptions options) {
        Objects.requireNonNull(engine);
        Objects.requireNonNull(monitor);
        Objects.requireNonNull(options);

        var standardLogger = createDefaultLogger(Deployment.class);

        var project = options.getProjectName();
        var stack = options.getStackName();
        var dryRun = options.isPreview();
        var logger = new DefaultEngineLogger(standardLogger);
        var runner = new DefaultRunner(logger, standardLogger);
        return new Deployment(new DeploymentState(project, stack, dryRun, engine, monitor, runner, logger));
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

    @Internal
    public EngineLogger getLogger() {
        return this.state.logger;
    }

    @Internal
    public Runner getRunner() {
        return this.state.runner;
    }

    private Config config; // TODO: move in a more appropriate place

    public Optional<String> getConfig(String fullKey) {
        return this.config.getConfig(fullKey);
    }

    public boolean isConfigSecret(String fullKey) {
        return this.config.isConfigSecret(fullKey);
    }

    @Nullable
    private Stack stack; // TODO: get rid of mutability, somehow

    @Internal
    public Stack getStack() {
        if (this.stack == null) {
            throw new IllegalStateException("Trying to acquire Deployment#getStack before 'run' was called.");
        }
        return this.stack;
    }

    @Internal
    public void setStack(Stack stack) {
        Objects.requireNonNull(stack);
        this.stack = stack;
    }

    private static Logger createDefaultLogger(Class<?> clazz) {
        var logger = Logger.getLogger(clazz.getName());
        logger.setLevel(
                Environment.getBooleanEnvironmentVariable("PULUMI_DOTNET_LOG_VERBOSE")
                  ? Level.FINEST
                        : Level.SEVERE
        );
        return logger;
    }

    private final ConcurrentHashMap<String, Boolean> featureSupport = new ConcurrentHashMap<>();

    @Internal
    private CompletableFuture<Boolean> monitorSupportsFeature(String feature) {
        if (!this.featureSupport.containsKey(feature)) {
            var request = SupportsFeatureRequest.newBuilder().setId(feature).build();
            var response = this.state.monitor.supportsFeatureAsync(request)
                    .thenApply(r -> {
                        var hasSupport = r.getHasSupport();
                        this.featureSupport.put(feature, hasSupport);
                        return hasSupport;
                    });
        }
        return CompletableFuture.completedFuture(this.featureSupport.get(feature));
    }

    @Internal
    CompletableFuture<Boolean> monitorSupportsResourceReferences() {
        return monitorSupportsFeature("resourceReferences");
    }


    @Override
    public <T> CompletableFuture<T> invokeAsync(String token, InvokeArgs args, @Nullable InvokeOptions options) {
        return null; // TODO
    }

    @Override
    public CompletableFuture<Void> invokeAsyncIgnore(String token, InvokeArgs args, @Nullable InvokeOptions options) {
        return null; // TODO
    }

    @Override
    public void readOrRegisterResource(Resource resource, boolean remote, Function<String, Resource> newDependency, ResourceArgs args, ResourceOptions opts) {
        // TODO
    }

    @Override
    public void registerResourceOutputs(Resource resource, Output<Map<String, Optional<Object>>> outputs) {
        // TODO
    }

    // TODO: this does not look like it belongs here
    private static Optional<ProviderResource> getProvider(String token, @Nullable InvokeOptions options) {
        return options != null ? options.internalGetProvider(token) : Optional.empty();
    }

    @ParametersAreNonnullByDefault
    private static class DeploymentState {
        public final String projectName;
        public final String stackName;
        public final boolean isDryRun;
        public final Engine engine;
        public final Monitor monitor;
        public final Runner runner;
        public final EngineLogger logger;

        private DeploymentState(
                String projectName,
                String stackName,
                boolean isDryRun,
                Engine engine,
                Monitor monitor,
                Runner runner,
                EngineLogger logger) {
            this.projectName = projectName;
            this.stackName = stackName;
            this.isDryRun = isDryRun;
            this.engine = engine;
            this.monitor = monitor;
            this.runner = runner;
            this.logger = logger;
        }
    }

    private static class InvokeException extends RuntimeException {
        public InvokeException(String message) {
            super(message);
        }
    }

    private static class DefaultRunner implements Runner {
        private static final int ProcessExitedSuccessfully = 0;
        private static final int ProcessExitedBeforeLoggingUserActionableMessage = 1;
        // Keep track if we already logged the information about an unhandled error to the user.
        // If so, we end with a different exit code. The language host recognizes this and will not print
        // any further messages to the user since we already took care of it.
        //
        // 32 was picked so as to be very unlikely to collide with any other error codes.
        private static final int ProcessExitedAfterLoggingUserActionableMessage = 32;

        private final EngineLogger engineLogger;
        private final Logger standardLogger;

        /**
         * The set of tasks (futures) that we have fired off. We issue futures in a Fire-and-Forget manner
         * to be able to expose a Synchronous @see {@link io.pulumi.resources.Resource} model for users.
         * i.e. a user just synchronously creates a resource, and we asynchronously kick off the work
         * to populate it.
         * This works well, however we have to make sure the console app
         * doesn't exit because it thinks there is no work to do.
         * <p/>
         * To ensure that doesn't happen, we have the main entrypoint of the app just
         * continuously, asynchronously loop, waiting for these tasks to complete, and only
         * exiting once the set becomes empty.
         */
        private final Map<CompletableFuture<Void>, List<String>> inFlightTasks = new HashMap<>(); // TODO: try to remove syncing later in code with Collections.synchronizedMap

        public DefaultRunner(EngineLogger engineLogger, Logger standardLogger) {
            this.engineLogger = engineLogger;
            this.standardLogger = standardLogger;
        }

        // TODO

        @Override
        public <T extends Stack> CompletableFuture<Integer> runAsync(Supplier<T> stackFactory) {
            try {
                var stack = stackFactory.get();
                // Stack doesn't call RegisterOutputs, so we register them on its behalf.
                stack.internalRegisterPropertyOutputs();
                registerTask(String.format("runAsync: %s, %s", stack.getType(), stack.getName()), stack.internalGetOutputs().internalGetDataAsync());
            } catch (Exception ex) {
                return handleExceptionAsync(ex);
            }

            return whileRunningAsync();
        }

        @Override
        public CompletableFuture<Integer> runAsyncFuture(Supplier<CompletableFuture<Map<String, Optional<Object>>>> callback, @Nullable StackOptions options) {
            var stack = new Stack(callback, options);
            registerTask(String.format("runAsyncFuture: %s, %s", stack.getType(), stack.getName()), stack.internalGetOutputs().internalGetDataAsync());
            return whileRunningAsync();
        }

        @Override
        public <T> void registerTask(String description, CompletableFuture<T> task) {
            standardLogger.log(Level.FINEST, String.format("Registering task: '%s'", description));

            synchronized (this.inFlightTasks) {
                // We may get several of the same tasks with different descriptions.  That can
                // happen when the runtime reuses cached tasks that it knows are value-identical
                // (for example a completed future). In that case, we just store all the
                // descriptions.
                // We'll print them all out as done once this task actually finishes.
                inFlightTasks.compute((CompletableFuture<Void>) task, (ignore, descriptions) -> {
                    if (descriptions == null) {
                        return Lists.newArrayList(description);
                    } else {
                        descriptions.add(description);
                        return descriptions;
                    }
                });
            }
        }

        private CompletableFuture<Integer> whileRunningAsync() {
            List<CompletableFuture<Void>> batches = new ArrayList<>();
            List<CompletableFuture<Void>> tasks = new ArrayList<>();

            // Keep looping as long as there are outstanding tasks that are still running.
            while (true) {
                tasks.clear();
                synchronized (inFlightTasks) {
                    if (inFlightTasks.size() == 0) {
                        // No more tasks in flight: exit the loop.
                        break;
                    }

                    // Grab all the tasks we currently have running.
                    tasks.addAll(inFlightTasks.keySet());
                }

                Consumer<CompletableFuture<Void>> handleCompletion = task -> {
                    try {
                        // Wait for the task completion.
                        task.join();

                        // Log the descriptions of completed tasks.
                        List<String> descriptions;
                        synchronized (inFlightTasks) {
                            descriptions = inFlightTasks.get(task);
                        }
                        for (var description : descriptions) {
                            standardLogger.log(Level.FINEST, String.format("Completed task: %s", description));
                        }
                    } finally {
                        // Once finished, remove the task from the set of tasks that are running.
                        synchronized (this.inFlightTasks) {
                            this.inFlightTasks.remove(task);
                        }
                    }
                };

                // Wait for one of the two events to happen:
                // 1. All tasks in the list complete successfully, or
                // 2. Any task throws an exception.
                // So the resulting semantics is that we complete
                // when remaining count is zero, or when an exception is thrown.
                try {
                    // Await all task and realize any exceptions they may have thrown.
                    batches.add(CompletableFutures
                            .allOf(tasks)
                            .thenAccept(ts -> ts.forEach(handleCompletion)));
                } catch (Exception e) {
                    // if it threw, report it as necessary, then quit.
                    return handleExceptionAsync(e);
                }
            }

            // Getting error information from a logger is slightly ugly, but that's what C# implementation does
            Supplier<Integer> exitCode = () -> this.engineLogger.hasLoggedErrors() ? 1 : 0;

            // There were no more tasks we were waiting on.
            // Quit out, reporting if we had any errors or not.
            return CompletableFutures
                    .allOf(batches)
                    .thenApply(unused -> exitCode.get());
        }

        private CompletableFuture<Integer> handleExceptionAsync(Exception exception) {
            Function<Void, Integer> exitMessageAndCode = unused -> {
                standardLogger.log(Level.FINE, "Returning from program after last error");
                return ProcessExitedAfterLoggingUserActionableMessage;
            };

            if (exception instanceof LogException) {
                // We got an error while logging itself. Nothing to do here but print some errors and fail entirely.
                standardLogger.log(Level.SEVERE, String.format(
                        "Error occurred trying to send logging message to engine: %s", exception.getMessage()));
                return CompletableFuture.supplyAsync(() -> {
                    System.err.printf("Error occurred trying to send logging message to engine: %s%n", exception);
                    exception.printStackTrace();
                    return ProcessExitedBeforeLoggingUserActionableMessage;
                });
            }

            // For the rest of the issue we encounter log the problem to the error stream. if we
            // successfully do this, then return with a special error code stating as such so that
            // our host doesn't print out another set of errors.
            //
            // Note: if these logging calls fail, they will just end up bubbling up an exception
            // that will be caught by nothing. This will tear down the actual process with a
            // non-zero error which our host will handle properly.
            if (exception instanceof RunException) {
                // Always hide the stack for RunErrors.
                return engineLogger
                        .errorAsync(exception.getMessage())
                        .thenApply(exitMessageAndCode);
            } else if (exception instanceof ResourceException) {
                var resourceEx = (ResourceException) exception;
                var message = resourceEx.isHideStack() ? resourceEx.getMessage() : getStackTrace(resourceEx);
                return engineLogger
                        .errorAsync(message, resourceEx.getResource().orElse(null))
                        .thenApply(exitMessageAndCode);
            } else {
                var pid = ProcessHandle.current().pid();
                var command = ProcessHandle.current().info().commandLine().orElse("unknown");
                return engineLogger
                        .errorAsync(String.format(
                                "Running program [PID: %d](%s) failed with an unhandled exception:\n%s",
                                pid, command, Exceptions.getStackTrace(exception)))
                        .thenApply(exitMessageAndCode);
            }
        }
    }

    private static class DefaultEngineLogger implements EngineLogger {
        private final Logger standardLogger;

        public DefaultEngineLogger(Logger standardLogger) {
            this.standardLogger = standardLogger;
        }

        // TODO
        @Override
        public boolean hasLoggedErrors() {
            return false;
        }

        @Override
        public CompletableFuture<Void> debugAsync(String message) {
            return null;
        }

        @Override
        public CompletableFuture<Void> infoAsync(String message) {
            return null;
        }

        @Override
        public CompletableFuture<Void> warnAsync(String message) {
            return null;
        }

        @Override
        public CompletableFuture<Void> errorAsync(String message) {
            return null;
        }

        @Override
        public CompletableFuture<Void> debugAsync(String message, @Nullable Resource resource) {
            return null;
        }

        @Override
        public CompletableFuture<Void> infoAsync(String message, @Nullable Resource resource) {
            return null;
        }

        @Override
        public CompletableFuture<Void> warnAsync(String message, @Nullable Resource resource) {
            return null;
        }

        @Override
        public CompletableFuture<Void> errorAsync(String message, @Nullable Resource resource) {
            return null;
        }

        @Override
        public CompletableFuture<Void> debugAsync(String message, @Nullable Resource resource, @Nullable Integer streamId, @Nullable Boolean ephemeral) {
            return null;
        }

        @Override
        public CompletableFuture<Void> infoAsync(String message, @Nullable Resource resource, @Nullable Integer streamId, @Nullable Boolean ephemeral) {
            return null;
        }

        @Override
        public CompletableFuture<Void> warnAsync(String message, @Nullable Resource resource, @Nullable Integer streamId, @Nullable Boolean ephemeral) {
            return null;
        }

        @Override
        public CompletableFuture<Void> errorAsync(String message, @Nullable Resource resource, @Nullable Integer streamId, @Nullable Boolean ephemeral) {
            return null;
        }
    }

    private static class Config {

        /**
         * The environment variable key that the language plugin uses to set configuration values.
         */
        private static final String ConfigEnvKey = "PULUMI_CONFIG";

        /**
         * The environment variable key that the language plugin uses to set the list of secret configuration keys.
         */
        private static final String ConfigSecretKeysEnvKey = "PULUMI_CONFIG_SECRET_KEYS";

        private ImmutableMap<String, String> allConfig;

        private ImmutableSet<String> configSecretKeys;

        private Config() {
            this(parseConfig(), parseConfigSecretKeys());
        }

        private Config(ImmutableMap<String, String> allConfig, ImmutableSet<String> configSecretKeys) {
            this.allConfig = allConfig;
            this.configSecretKeys = configSecretKeys;
        }

        /**
         * Returns a copy of the full config map.
         */
        @Internal
        ImmutableMap<String, String> getAllConfig() {
            return allConfig;
        }

        /**
         * Returns a copy of the config secret keys.
         */
        @Internal
        ImmutableSet<String> configSecretKeys() {
            return configSecretKeys;
        };

        /**
         * Sets a configuration variable.
         */
        @Internal
        void setConfig(String key, String value) { // TODO: can the setter be avoided?
            this.allConfig = new ImmutableMap.Builder<String, String>()
                    .putAll(this.allConfig)
                    .put(key, value)
                    .build();
        }

        /**
         * Appends all provided configuration.
         */
        @Internal
        void setAllConfig(ImmutableMap<String, String> config, @Nullable Iterable<String> secretKeys) { // TODO: can the setter be avoided?
            this.allConfig = new ImmutableMap.Builder<String, String>()
                    .putAll(this.allConfig)
                    .putAll(config)
                    .build();
            if (secretKeys != null) {
                this.configSecretKeys = new ImmutableSet.Builder<String>()
                        .addAll(this.configSecretKeys)
                        .addAll(secretKeys)
                        .build();
            }
        }

        public Optional<String> getConfig(String fullKey) {
            return Optional.ofNullable(this.allConfig.getOrDefault(fullKey, null));
        }

        public boolean isConfigSecret(String fullKey) {
            return this.configSecretKeys.contains(fullKey);
        }

        private static ImmutableMap<String, String> parseConfig() {
            var parsedConfig = ImmutableMap.<String, String>builder();
            var envConfig = Environment.getEnvironmentVariable(ConfigEnvKey);
            if (envConfig != null) {
                Gson gson = new Gson();
                var envObject = gson.fromJson(envConfig, JsonElement.class);
                for (var prop : envObject.getAsJsonObject().entrySet()) {
                    parsedConfig.put(cleanKey(prop.getKey()), prop.getValue().toString());
                }
            }

            return parsedConfig.build();
        }

        private static ImmutableSet<String> parseConfigSecretKeys() {
            var parsedConfigSecretKeys = ImmutableSet.<String>builder();
            var envConfigSecretKeys = Environment.getEnvironmentVariable(ConfigSecretKeysEnvKey);
            if (envConfigSecretKeys != null) {
                Gson gson = new Gson();
                var envObject = gson.fromJson(envConfigSecretKeys, JsonElement.class);
                for (var element : envObject.getAsJsonArray()) {
                    parsedConfigSecretKeys.add(element.getAsString());
                }
            }

            return parsedConfigSecretKeys.build();
        }

        /**
         * CleanKey takes a configuration key, and if it is of the form "(string):config:(string)"
         * removes the ":config:" portion. Previously, our keys always had the string ":config:" in
         * them, and we'd like to remove it. However, the language host needs to continue to set it
         * so we can be compatible with older versions of our packages. Once we stop supporting
         * older packages, we can change the language host to not add this :config: thing and
         * remove this function.
         */
        private static String cleanKey(String key) {
            var idx = key.indexOf(":");
            if (idx > 0 && key.substring(idx + 1).startsWith("config:")) {
                return key.substring(0, idx) + ":" + key.substring(idx + 1 + "config:".length());
            }

            return key;
        }
    }
}
