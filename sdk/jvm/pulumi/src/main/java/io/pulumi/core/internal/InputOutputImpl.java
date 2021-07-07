package io.pulumi.core.internal;

import io.grpc.Internal;
import io.pulumi.core.InputOutput;
import io.pulumi.resources.Resource;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public abstract class InputOutputImpl<T, IO extends InputOutput<T, IO> & Copyable<IO>>
        implements InputOutput<T, IO>, TypedInputOutput<T>, UntypedInputOutput {
    protected final CompletableFuture<InputOutputData<T>> dataFuture;

    protected InputOutputImpl(T value) {
        this(value, false);
    }

    protected InputOutputImpl(T value, boolean isSecret) {
        this(CompletableFuture.completedFuture(Objects.requireNonNull(value)), isSecret);
    }

    protected InputOutputImpl(CompletableFuture<T> value, boolean isSecret) {
        this(InputOutputData.ofAsync(Objects.requireNonNull(value), isSecret));
    }

    protected InputOutputImpl(InputOutputData<T> dataFuture) {
        this(CompletableFuture.completedFuture(Objects.requireNonNull(dataFuture)));
    }

    protected InputOutputImpl(CompletableFuture<InputOutputData<T>> dataFuture) {
        this.dataFuture = Objects.requireNonNull(dataFuture);
    }

    protected abstract IO newInstance(CompletableFuture<InputOutputData<T>> dataFuture);

    public IO copy() {
        // we do not copy the OutputData, because it should be immutable
        return newInstance(this.dataFuture.copy()); // TODO: is the copy deep enough
    }

    /**
     * @return the secret-ness status of the given output
     */
    public CompletableFuture<Boolean> isSecretAsync() {
        return this.dataFuture.thenApply(InputOutputData::isSecret);
    }

    /**
     * @return true if the given output is empty (null)
     */
    public CompletableFuture<Boolean> isEmptyAsync() {
        return this.dataFuture.thenApply(InputOutputData::isEmpty);
    }

    public IO unsecret() {
        return internalWithIsSecret(CompletableFuture.completedFuture(false));
    }

    protected IO internalWithIsSecret(CompletableFuture<Boolean> isSecretFuture) {
        return newInstance(
                isSecretFuture.thenCompose(
                        secret -> this.dataFuture.thenApply(
                                d -> d.withIsSecret(secret)
                        )
                )
        );
    }

    @Internal
    public CompletableFuture<InputOutputData<T>> internalGetDataAsync() {
        return this.dataFuture;
    }

    @Internal
    public CompletableFuture<T> internalGetValueAsync() {
        return this.dataFuture.thenApply(InputOutputData::getValue);
    }

    @Override
    @Internal
    public CompletableFuture<Set<Resource>> internalGetResourcesUntypedAsync() {
        return this.dataFuture.thenApply(InputOutputData::getResources);
    }

    @Override
    @Internal
    public CompletableFuture<InputOutputData<Object>> internalGetDataUntypedAsync() {
        return this.dataFuture.thenApply(inputOutputData -> inputOutputData.apply(t -> t));
    }

    // Static section -------


}
