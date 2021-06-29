package io.pulumi.deployment;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Optional;
import java.util.Stack;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public interface Runner {
    void registerTask(String description, CompletableFuture<Void> task);
    CompletableFuture<Integer> runAsync(Function<CompletableFuture<Map<String, Object>>> func, @Nullable StackOptions options);
    CompletableFuture<Integer> runAsync<TStack>() where TStack : Stack, new();
    CompletableFuture<Integer> runAsync<TStack>(Function<TStack> stackFactory) where TStack : Stack;
    CompletableFuture<Integer> runAsync<TStack>(IServiceProvider serviceProvider) where TStack : Stack;
}
