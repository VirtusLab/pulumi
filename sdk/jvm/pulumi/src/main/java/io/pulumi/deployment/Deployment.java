package io.pulumi.deployment;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.protobuf.Struct;
import io.grpc.Internal;
import io.pulumi.Log;
import io.pulumi.Stack;
import io.pulumi.core.Output;
import io.pulumi.core.Tuples;
import io.pulumi.core.Tuples.Tuple2;
import io.pulumi.core.internal.*;
import io.pulumi.deployment.internal.*;
import io.pulumi.exceptions.LogException;
import io.pulumi.exceptions.ResourceException;
import io.pulumi.exceptions.RunException;
import io.pulumi.resources.*;
import io.pulumi.serialization.internal.Serializer;
import io.pulumi.test.internal.TestOptions;
import pulumirpc.EngineOuterClass.LogRequest;
import pulumirpc.EngineOuterClass.LogSeverity;
import pulumirpc.Provider;
import pulumirpc.Resource.SupportsFeatureRequest;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Objects;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
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

            var config = new Deployment.Config();

            // FIXME: use different logger, not j.u.l!
            var standardLogger = Logger.getLogger(Deployment.class.getName());

            standardLogger.log(Level.FINEST, "Creating deployment engine");
            var engine = new GrpcEngine(engineTarget);
            standardLogger.log(Level.FINEST, "Created deployment engine");

            standardLogger.log(Level.FINEST, "Creating deployment monitor");
            var monitor = new GrpcMonitor(monitorTarget);
            standardLogger.log(Level.FINEST, "Created deployment monitor");

            return new DeploymentState(config, standardLogger, project, stack, dryRun, engine, monitor);
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
        return internalTestDeployment(engine, monitor, new TestOptions());
    }

    /**
     * This constructor is called from @see #testAsync(IMocks, Func{IRunner, Task{int}}, TestOptions?)"/>
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
        var config = new Deployment.Config();
        return new Deployment(
                new DeploymentState(config, standardLogger, project, stack, dryRun, engine, monitor)
        );
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

    public Optional<String> getConfig(String fullKey) {
        return this.state.config.getConfig(fullKey);
    }

    public boolean isConfigSecret(String fullKey) {
        return this.state.config.isConfigSecret(fullKey);
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
    public CompletableFuture<Void> invokeAsyncIgnore(String token, InvokeArgs args, @Nullable InvokeOptions options) {
        return invokeRawAsync(token, args, options).thenApply(unused -> null);
    }

    @Override
    public <T> CompletableFuture<T> invokeAsync(String token, InvokeArgs args, @Nullable InvokeOptions options) {
        /*return invokeRawAsync(token, args, options).thenApply(
                struct ->
        );*/

        // TODO
        /*
            var result = await InvokeRawAsync(token, args, options);

            var data = Converter.ConvertValue<T>($"{token} result", new Value { StructValue = result });
            return data.Value;
        */
        throw new UnsupportedOperationException();
    }


    private CompletableFuture<Struct> invokeRawAsync(String token, @Nullable InvokeArgs args, @Nullable InvokeOptions options) {
        var label = String.format("Invoking function: token='%s' asynchronously", token);
        Log.debug(label);

        // Be resilient to misbehaving callers.
        args = args == null ? InvokeArgs.Empty : args;

        // Wait for all values to be available, and then perform the RPC.
        var serializedFuture = args.internalTypedOptionalToMapAsync()
                .thenCompose(argsDict ->
                        this.monitorSupportsResourceReferences()
                                .thenCompose(supportsResourceReferences ->
                                        serializeAllPropertiesAsync(
                                                String.format("invoke:%s", token), argsDict, supportsResourceReferences
                                        ))
                );

        var providerFuture = ProviderResource.internalRegisterAsync(
                getProvider(token, options).orElse(null));

        return CompletableFuture.allOf(serializedFuture, providerFuture)
                .thenCompose(unused -> {
                    var serialized = serializedFuture.join();
                    var provider = providerFuture.join();

                    Log.debug(String.format("Invoke RPC prepared: token='%s'", token) +
                            (DeploymentState.ExcessiveDebugOutput ? String.format(", obj='%s'", serialized) : ""));
                    return this.state.monitor.invokeAsync(Provider.InvokeRequest.newBuilder()
                            .setTok(token)
                            .setProvider(provider == null ? "" : provider)
                            .setVersion(options == null ? "" : options.getVersion().orElse(""))
                            .setArgs(serialized)
                            .setAcceptResources(!DeploymentState.DisableResourceReferences)
                            .build());
                }).thenApply(response -> {
                    if (response.getFailuresCount() > 0) {
                        StringBuilder reasons = new StringBuilder();
                        for (var reason : response.getFailuresList()) {
                            if (!Objects.equals(reasons.toString(), "")) {
                                reasons.append("; ");
                            }
                            reasons.append(String.format("%s (%s)", reason.getReason(), reason.getProperty()));
                        }

                        throw new InvokeException(String.format("Invoke of '%s' failed: %s", token, reasons));
                    }
                    return response.getReturn();
                });
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

    /**
     * Walks the props object passed in, awaiting all interior promises besides those
     * for @see {@link Resource#getUrn()} and @see {@link CustomResource#getId()},
     * creating a reasonable POJO object that can be remoted over to registerResource.
     */
    private static CompletableFuture<SerializationResult> serializeResourcePropertiesAsync(
            String label, Map<String, Optional<Object>> args, boolean keepResources
    ) {
        Predicate<String> filter = key -> !Constants.IdPropertyName.equals(key) && !Constants.UrnPropertyName.equals(key);
        return serializeFilteredPropertiesAsync(label, args, filter, keepResources);
    }

    private static CompletableFuture<Struct> serializeAllPropertiesAsync(
            String label, Map<String, Optional<Object>> args, boolean keepResources
    ) {
        return serializeFilteredPropertiesAsync(label, args, unused -> true, keepResources)
                .thenApply(result -> result.serialized);
    }

    /**
     * walks the props object passed in, awaiting all interior promises for properties
     * with keys that match the provided filter, creating a reasonable POJO object that
     * can be remoted over to registerResource.
     */
    private static CompletableFuture<SerializationResult> serializeFilteredPropertiesAsync(
            String label, Map<String, Optional<Object>> args, Predicate<String> acceptKey, boolean keepResources) {
        var propertyToDependentResources = ImmutableMap.<String, Set<Resource>>builder();
        var resultFutures = new HashMap<String, CompletableFuture</* @Nullable */ Object>>();
        var temporaryResources = new HashMap<String, Set<Resource>>();

        for (var arg : args.entrySet()) {
            var key = arg.getKey();
            var value = arg.getValue();
            if (acceptKey.test(key)) {
                // We treat properties with null values as if they do not exist.
                var serializer = new Serializer(DeploymentState.ExcessiveDebugOutput);
                resultFutures.put(key, serializer.serializeAsync(String.format("%s.%s", label, key), value, keepResources));
                temporaryResources.put(key, serializer.dependentResources); // FIXME: this is ugly
            }
        }

        return CompletableFutures.allOf(resultFutures)
                .thenApply(
                        completedFutures -> {
                            var results = new HashMap<String, /* @Nullable */ Object>();
                            for (var entry : completedFutures.entrySet()) {
                                var key = entry.getKey();
                                var value = /* @Nullable */ entry.getValue().join();
                                // We treat properties with null values as if they do not exist.
                                if (value != null) {
                                    results.put(key, value);
                                    propertyToDependentResources.put(key, temporaryResources.get(key)); // FIXME: this is ugly
                                }
                            }
                            return results;
                        })
                .thenApply(
                        results -> new SerializationResult(
                                Serializer.createStruct(results),
                                propertyToDependentResources.build()
                        )
                );
    }

    private static class InvokeException extends RuntimeException {
        public InvokeException(String message) {
            super(message);
        }
    }

    @ParametersAreNonnullByDefault
    private static class DeploymentState {
        public static final boolean DisableResourceReferences = getBooleanEnvironmentVariable("PULUMI_DISABLE_RESOURCE_REFERENCES");
        public static final boolean ExcessiveDebugOutput = false;

        public final Deployment.Config config;
        public final String projectName;
        public final String stackName;
        public final boolean isDryRun;
        public final Engine engine;
        public final Monitor monitor;
        public final Runner runner;
        public final EngineLogger logger;

        private DeploymentState(
                Deployment.Config config,
                Logger standardLogger,
                String projectName,
                String stackName,
                boolean isDryRun,
                Engine engine,
                Monitor monitor) {
            Objects.requireNonNull(standardLogger);
            this.config = Objects.requireNonNull(config);
            this.projectName = Objects.requireNonNull(projectName);
            this.stackName = Objects.requireNonNull(stackName);
            this.isDryRun = isDryRun;
            this.engine = Objects.requireNonNull(engine);
            this.monitor = Objects.requireNonNull(monitor);
            this.runner = new DefaultRunner(this, standardLogger);
            this.logger = new DefaultEngineLogger(this, standardLogger);
        }
    }

    @ParametersAreNonnullByDefault
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

        public DefaultRunner(DeploymentState deployment, Logger standardLogger) {
            this.engineLogger = Objects.requireNonNull(deployment).logger;
            this.standardLogger = Objects.requireNonNull(standardLogger);
        }

        // TODO

        @Override
        public <T extends Stack> CompletableFuture<Integer> runAsync(Supplier<T> stackFactory) {
            try {
                var stack = stackFactory.get();
                // Stack doesn't call RegisterOutputs, so we register them on its behalf.
                stack.internalRegisterPropertyOutputs();
                registerTask(String.format("runAsync: %s, %s", stack.getType(), stack.getName()),
                        TypedInputOutput.cast(stack.internalGetOutputs()).internalGetDataAsync());
            } catch (Exception ex) {
                return handleExceptionAsync(ex);
            }

            return whileRunningAsync();
        }

        @Override
        public CompletableFuture<Integer> runAsyncFuture(Supplier<CompletableFuture<Map<String, Optional<Object>>>> callback, @Nullable StackOptions options) {
            var stack = new Stack(callback, options);
            registerTask(String.format("runAsyncFuture: %s, %s", stack.getType(), stack.getName()),
                    TypedInputOutput.cast(stack.internalGetOutputs()).internalGetDataAsync());
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
                inFlightTasks.compute(task.thenApply(unused -> null), (ignore, descriptions) -> {
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

    @ParametersAreNonnullByDefault
    @VisibleForTesting
    static class DefaultEngineLogger implements EngineLogger {
        private final Runner runner;
        private final Engine engine;
        private final Logger standardLogger;
        private final AtomicInteger errorCount;

        // We serialize all logging tasks so that the engine doesn't hear about them out of order.
        // This is necessary for streaming logs to be maintained in the right order.
        private CompletableFuture<Void> lastLogTask = CompletableFuture.completedFuture(null);
        private final Object logGate = new Object(); // lock target

        public DefaultEngineLogger(DeploymentState deployment, Logger standardLogger) {
            this(deployment.runner, deployment.engine, standardLogger);
        }

        @VisibleForTesting
        DefaultEngineLogger(Runner runner, Engine engine, Logger standardLogger) {
            this.runner = runner;
            this.engine = engine;
            this.standardLogger = standardLogger;
            this.errorCount = new AtomicInteger(0);
        }

        @Override
        public boolean hasLoggedErrors() {
            return errorCount.get() > 0;
        }

        @Override
        public int getErrorCount() {
            return errorCount.get();
        }

        @Override
        public CompletableFuture<Void> debugAsync(String message, @Nullable Resource resource, @Nullable Integer streamId, @Nullable Boolean ephemeral) {
            standardLogger.log(Level.FINEST, message);
            return logImplAsync(LogSeverity.DEBUG, message, resource, streamId, ephemeral);
        }

        @Override
        public CompletableFuture<Void> infoAsync(String message, @Nullable Resource resource, @Nullable Integer streamId, @Nullable Boolean ephemeral) {
            standardLogger.log(Level.INFO, message);
            return logImplAsync(LogSeverity.INFO, message, resource, streamId, ephemeral);
        }

        @Override
        public CompletableFuture<Void> warnAsync(String message, @Nullable Resource resource, @Nullable Integer streamId, @Nullable Boolean ephemeral) {
            standardLogger.log(Level.WARNING, message);
            return logImplAsync(LogSeverity.WARNING, message, resource, streamId, ephemeral);
        }

        @Override
        public CompletableFuture<Void> errorAsync(String message, @Nullable Resource resource, @Nullable Integer streamId, @Nullable Boolean ephemeral) {
            standardLogger.log(Level.SEVERE, message);
            return logImplAsync(LogSeverity.ERROR, message, resource, streamId, ephemeral);
        }

        private CompletableFuture<Void> logImplAsync(LogSeverity severity, String message,
                                                     @Nullable Resource resource, @Nullable Integer streamId,
                                                     @Nullable Boolean ephemeral
        ) {
            // Serialize our logging tasks so that streaming logs appear in order.
            CompletableFuture<Void> task;
            synchronized (logGate) {
                if (severity == LogSeverity.ERROR) {
                    this.errorCount.incrementAndGet();
                }

                // TODO: C# uses a 'Task.Run' here (like CompletableFuture.runAsync/supplyAsync?)
                //       so that "we don't end up aggressively running the actual logging while holding this lock."
                //       Is something similar required in Java or thenComposeAsync is enough?
                this.lastLogTask = this.lastLogTask.thenComposeAsync(
                        unused -> logAsync(severity, message, resource, streamId, ephemeral)
                );
                task = this.lastLogTask;
            }

            this.runner.registerTask(message, task);
            return task;
        }

        private CompletableFuture<Void> logAsync(LogSeverity severity, String message,
                                                 @Nullable Resource resource, @Nullable Integer streamId,
                                                 @Nullable Boolean ephemeral) {
            try {
                return tryGetResourceUrnAsync(resource)
                        .thenCompose(
                                urn -> engine.logAsync(LogRequest.newBuilder()
                                        .setSeverity(severity)
                                        .setMessage(message)
                                        .setUrn(urn)
                                        .setStreamId(streamId == null ? 0 : streamId)
                                        .setEphemeral(ephemeral != null && ephemeral).build()
                                )
                        );
            } catch (Exception e) {
                synchronized (logGate) {
                    // mark that we had an error so that our top level process quits with an error
                    // code.
                    errorCount.incrementAndGet();
                }

                // We have a potential pathological case with logging. Consider if logging a
                // message itself throws an error.  If we then allow the error to bubble up, our top
                // level handler will try to log that error, which can potentially lead to an error
                // repeating unendingly. So, to prevent that from happening, we report a very specific
                // exception that the top level can know about and handle specially.
                throw new LogException(e);
            }
        }

        private static CompletableFuture<String> tryGetResourceUrnAsync(@Nullable Resource resource) {
            if (resource != null) {
                try {
                    return TypedInputOutput.cast(resource.getUrn()).internalGetValueAsync();
                } catch (Throwable ignore) {
                    // getting the urn for a resource may itself fail, in that case we don't want to
                    // fail to send an logging message. we'll just send the logging message unassociated
                    // with any resource.
                }
            }

            return CompletableFuture.completedFuture("");
        }
    }

    @ParametersAreNonnullByDefault
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
        }

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

    @ParametersAreNonnullByDefault
    private static class SerializationResult {
        public final Struct serialized;
        public final ImmutableMap<String, Set<Resource>> propertyToDependentResources;

        public SerializationResult(
                Struct result,
                ImmutableMap<String, Set<Resource>> propertyToDependentResources) {
            this.serialized = result;
            this.propertyToDependentResources = propertyToDependentResources;
        }

        public Tuple2<Struct, ImmutableMap<String, Set<Resource>>> deconstruct() {
            return Tuples.of(serialized, propertyToDependentResources);
        }
    }
}
