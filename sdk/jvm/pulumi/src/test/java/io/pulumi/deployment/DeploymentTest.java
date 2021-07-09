package io.pulumi.deployment;

import io.pulumi.deployment.internal.Engine;
import io.pulumi.deployment.internal.Monitor;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;

public class DeploymentTest {

    @Test
    void testDeploymentInstancePropertyIsProtected() {
        // confirm we cannot retrieve deployment instance early
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(Deployment::getInstance)
                .withMessageContaining("Trying to acquire Deployment#getInstance before");

        var engine = mock(Engine.class);
        var monitor = mock(Monitor.class);
        var deployment = Deployment.internalTestDeployment(engine, monitor);

        var task = Deployment.internalCreateRunnerAndRunAsync(
                () -> deployment,
                runner -> {
                    // try to double-set the Deployment#instance
                    Deployment.setInstance(new DeploymentInstance(deployment));
                    return CompletableFuture.completedFuture(1);
                }
        );

        // should not throw until awaited
        assertThatExceptionOfType(CompletionException.class)
                .isThrownBy(task::join)
                .withCauseInstanceOf(IllegalStateException.class)
                .withMessageContaining("Deployment.getInstance should only be set once");
    }
}
