package io.pulumi.serialization;



import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableList;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import io.pulumi.core.AssetOrArchive;
import io.pulumi.deployment.Deployment;
import io.pulumi.resources.ComponentResource;
import io.pulumi.resources.InputArgs;
import io.pulumi.resources.Resource;
import io.pulumi.core.Input;
import io.pulumi.core.Output;


public class Serializer {
    public final HashSet<Resource> dependentResource; // TODO: concurent dataset
   private final boolean excessiveDebugOutput;

   public Serializer(boolean excessiveDebugOutput) {
       this.dependentResource = new HashSet<Resource>();
       this.excessiveDebugOutput = excessiveDebugOutput;
   }

   public CompletableFuture<Object> serializeAsync(String ctx, @Nullable Object prop, boolean keepResources) {
    if (prop == null || isPrimitive(prop)) {
        logDebug(String.format("Serialize property[%s]: primitive=%s", ctx, prop));
        return CompletableFuture.completedFuture(prop);
    }
    if (prop instanceof InputArgs) {
        return serializeInputArgsAsync(ctx, (InputArgs) prop, keepResources);
    }

    if (prop instanceof AssetOrArchive) {
        return serializeAssetOrArchiveAsync(ctx, (AssetOrArchive) prop, keepResources);
    }
    
    if (prop instanceof Future) {
        String msg = String.format("Tasks are not allowed inside ResourceArgs. Please wrap your Task in an Output:\n\t%s", ctx);
        throw new IllegalArgumentException(msg);
    }

    if (prop instanceof Input) {
        logDebug(String.format("Serialize property[%s]: primitive=%s", ctx, prop)); // TODO
        Output<Object> output = Output.create((Input) prop);
        return serializeAsync(ctx, output, keepResources);
    }

    // TODO union, json

    if (prop instanceof Output) {
        logDebug(String.format("Serialize property[%s]: primitive=%s", ctx, prop)); // TODO

        Output<Object> output = (Output) prop;
        CompletableFuture<OutputData<Object>> data = output.getDataAsync();
        return data.thenCompose(d -> {
            this.dependentResource.addAll(d.resources);
            boolean isKnown = d.isKnown();
            boolean isSecret = d.isSecret();

            if (!isKnown) {
                return CompletableFuture.completedFuture(Constants.unknownValue);
            }
            CompletableFuture<Object> value = serializeAsync(ctx + ".id", d.value, keepResources);
            
            if (isSecret) {
                return value.thenApply(v -> ImmutableMap.of(Constants.specialSigKey, Constants.specialSecretSig, Constants.secretValueName, v));
            }
            return value;
        });
    }

    if (prop instanceof ComponentResource) {
        logDebug(String.format("Serialize property[%s]: Encountered ComponentResource", ctx)); // TODO

        ComponentResource componentResource = (ComponentResource) prop;
        CompletableFuture<Object> urn = serializeAsync(ctx + ".urn", componentResource.urn, keepResources);
        if (keepResources) {
            return urn.thenApply(u -> ImmutableMap.of(Constants.specialSigKey, Constants.specialResourceSig, Constants.resourceUrnName, u));
        }
        return urn;
 
    }

    if (prop instanceof Map) {
        return serializeMapAsync(ctx, (Map<Object, Object>) prop, keepResources);
    }

    if (prop instanceof List) {
        return serializeListAsync(ctx, (List<Object>) prop, keepResources);
    }

    // TODO enum

    throw new IllegalArgumentException(String.format("%s is not a supported argument type.\n\t%s", "TODO", ctx));
   }

private CompletableFuture<Object> serializeInputArgsAsync(String ctx, InputArgs args, boolean keepResources) {
    logDebug(String.format("Serialize property[%s]: Recursing into ResourceArgs", ctx));

    CompletableFuture<Map<Object,Object>> map = args.toMapAsync();
    
    return map.thenCompose(m -> serializeMapAsync(ctx, m, keepResources));
}

private CompletableFuture<Object> serializeAssetOrArchiveAsync(String ctx, AssetOrArchive prop, boolean keepResources) {
    logDebug(String.format("Serialize property[%s]: asset/archive=%s",ctx, prop.getClass().getName()));
    String propName = prop.propName;
    CompletableFuture<Object> value = serializeAsync(ctx + "." + propName, prop.value, keepResources);

    return value.thenApply(v -> ImmutableMap.of(Constants.specialSigKey, prop.sigKey, propName, v));
}

private CompletableFuture<Object> serializeMapAsync(String ctx, Map<Object, Object> prop, boolean keepResources) {
    logDebug(String.format("Serialize property[%s]: Hit dictionary", ctx));

    HashMap<String, CompletableFuture<Object>> interResults = new HashMap<String, CompletableFuture<Object>>();

    for (Map.Entry<Object, Object> entry : prop.entrySet()) {
        if(! (entry.getKey() instanceof String)) {
            throw new IllegalArgumentException(String.format("Maps are only supported with string keys:\n\t%s", ctx));
        }
        String stringKey = (String) entry.getKey();
        logDebug(String.format("Serialize property[%s]: object.%s", ctx, stringKey));

        CompletableFuture<Object> v = serializeAsync(ctx + "." + stringKey, entry.getValue(), keepResources);
        interResults.put(stringKey, v);
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
private CompletableFuture<Object> serializeListAsync(String ctx, List<Object> prop, boolean keepResources) {
    logDebug(String.format("Serialize property[%s]: Hit list", ctx));

    ArrayList<CompletableFuture<Object>> interResults = new ArrayList<CompletableFuture<Object>>();

    for (int i = 0; i < prop.size(); i++) {
        logDebug(String.format("Serialize property[%s]: array[%s]", ctx, i));

        CompletableFuture<Object> v = serializeAsync(ctx + "[" + i +"]", prop.get(i), keepResources);
        interResults.add(v);
    }
    return CompletableFuture.supplyAsync(() -> {
        CompletableFuture<Object>[] futuresArray = interResults.toArray(new CompletableFuture[interResults.size()]);
        CompletableFuture.allOf(futuresArray).join();
        ArrayList<Object> result = new ArrayList<Object>();
        for (int i = 0; i < prop.size(); i++) {
            if (interResults.get(i).join() != null) {
                result.add(interResults.get(i).join());
            }
        }

        return ImmutableList.copyOf(result);
    });

}

   private boolean isPrimitive(Object prop) {
       if ((prop instanceof Boolean) || (prop instanceof Integer) || (prop instanceof Double) || (prop instanceof String)) {
           return true;
       } else {
           return false;
       }
   }
   private void logDebug(String msg) {
       if (excessiveDebugOutput) {
           Deployment.INSTANCE.getLogger().debugAsync(msg, null, null, null);
       }
   }
}
