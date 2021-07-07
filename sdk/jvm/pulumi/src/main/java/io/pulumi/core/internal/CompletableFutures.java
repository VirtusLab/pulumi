package io.pulumi.core.internal;

import io.grpc.Internal;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Internal
public class CompletableFutures {
    private CompletableFutures() {
        throw new UnsupportedOperationException();
    }

    /**
     * @param futures tasks to await completion of
     * @return a future with all given nested futures completed
     */
    public static <T> CompletableFuture<Collection<CompletableFuture<T>>> allOf(Collection<CompletableFuture<T>> futures) {
        return CompletableFuture
                .allOf(futures.toArray(new CompletableFuture[futures.size()]))
                .thenApply(unused -> futures);
    }

    /**
     * @param futuresMap tasks to await completion of
     * @return a future with all given nested futures completed
     */
    public static <K, V> CompletableFuture<Map<K, CompletableFuture<V>>> allOf(Map<K, CompletableFuture<V>> futuresMap) {
        return CompletableFuture
                .allOf(futuresMap.values().toArray(new CompletableFuture[futuresMap.size()]))
                .thenApply(unused -> futuresMap);
    }
}
