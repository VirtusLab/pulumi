package io.pulumi.core.internal;

import io.grpc.Internal;
import io.pulumi.resources.Resource;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Internal interface to allow our code to operate on outputs in an untyped manner. Necessary
 * as there is no reasonable way to write algorithms over heterogeneous instantiations of
 * generic types.
 */
public interface UntypedOutput {
    @Internal
    CompletableFuture<Set<Resource>> internalGetResourcesUntypedAsync();

    /**
     * Returns an @see {@link io.pulumi.core.Output} unsafe equivalent to this,
     * except with our @see {@link OutputData#getValue()} casted to an Object.
     */
    @SuppressWarnings("rawtypes")
    @Internal
    CompletableFuture<OutputData> internalGetDataUntypedAsync();
}
