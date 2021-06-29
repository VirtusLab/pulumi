package io.pulumi.resources;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableList;

import io.pulumi.serialization.InputAttribute;
import io.pulumi.serialization.Serializer;

public abstract class InputArgs {
    private final ImmutableList<InputInfo> inputInfos;

    protected abstract void validateMember(Type memberType, String fullName);

    protected InputArgs() {
        // TODO
        inputInfos = ImmutableList.of();
    }

    public CompletableFuture<Map<Object, Object>> toMapAsync() {
        HashMap<String, CompletableFuture<Object>> interResults = new HashMap<String, CompletableFuture<Object>>();
        for (InputInfo info : inputInfos) {
            String fullName = String.format("[Input] %s.%s", this.getClass().getName(), info.memberName);
            
            Object value = info.getValue.apply(this);
            if (info.attribute.isRequired && value == null) {
                throw new IllegalArgumentException(String.format("%s is required but was not given a value", fullName));
            }
            if (info.attribute.json) {
                interResults.put(info.attribute.name, convertToJsonAsync(fullName, value));
            } else {
                interResults.put(info.attribute.name, CompletableFuture.completedFuture(value));
            }
        }
        return CompletableFuture.supplyAsync(() -> {
            CompletableFuture<Object>[] futuresArray = interResults.values().toArray(new CompletableFuture[interResults.size()]);
            CompletableFuture.allOf(futuresArray).join();
            HashMap<String, Object> result = new HashMap<String, Object>();
            for (Map.Entry<String, CompletableFuture<Object>> entry : interResults.entrySet()) {
                if (entry.getValue().join() != null) {
                    result.put(entry.getKey(), entry.getValue().join());
                }
            }
     
            return ImmutableMap.copyOf(result);
        });
    }

    private CompletableFuture<Object> convertToJsonAsync(String context, Object input) {
        if (input == null) {
            return CompletableFuture.completedFuture(null);
        }
        Serializer serializer = new Serializer(false);
        CompletableFuture<Object> obj = serializer.serializeAsync(context, input, false);
        // TODO 
        return obj;
    }

    private class InputInfo {
        private InputAttribute attribute;
        private final Type memberType;
        private final String memberName;
        private final Function<Object, Object> getValue;

        private InputInfo(InputAttribute attribute, Type memberType, String memberName, Function<Object, Object> getValue) {
            this.attribute = attribute;
            this.memberName = memberName;
            this.memberType = memberType;
            this.getValue = getValue;
        }
    }
}