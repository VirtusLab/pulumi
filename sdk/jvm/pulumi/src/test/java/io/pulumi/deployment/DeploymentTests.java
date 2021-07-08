package io.pulumi.deployment;

import io.pulumi.deployment.internal.DeploymentInternalInternal;
import io.pulumi.deployment.internal.Engine;
import io.pulumi.deployment.internal.EngineLogger;
import io.pulumi.deployment.internal.Runner;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class DeploymentTests {

    private static final Logger Log = Logger.getLogger(DeploymentTests.class.getName());

    private DeploymentTests() {
        throw new UnsupportedOperationException();
    }

    public static Deployment.DefaultEngineLogger setupDeploymentMocks() {
        return setupDeploymentMocks(false);
    }

    public static Deployment.DefaultEngineLogger setupDeploymentMocks(boolean dryRun) {

        var runner = mock(Runner.class);
        doNothing().when(runner).registerTask(anyString(), any(CompletableFuture.class));

        var engine = mock(Engine.class);
        var logger = new Deployment.DefaultEngineLogger(runner, engine, Log);
        Log.setLevel(Level.FINEST);

        var mock = mock(DeploymentInternalInternal.class);
        when(mock.isDryRun()).thenReturn(dryRun);
        when(mock.getRunner()).thenReturn(runner);
        when(mock.getLogger()).thenReturn(logger);

        DeploymentTests.setInstance(new DeploymentInstance(mock));

        return logger;
    }

    public static void setInstance(DeploymentInstance instance) {
        Deployment.setInstance(instance);
    }

    public static void printErrorCount(EngineLogger logger) {
        if (logger.hasLoggedErrors()) {
            System.out.println("logger.errorCount=" + logger.getErrorCount());
        }
    }
}