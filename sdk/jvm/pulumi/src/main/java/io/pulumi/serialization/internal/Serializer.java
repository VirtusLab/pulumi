package io.pulumi.serialization.internal;

import com.google.common.annotations.VisibleForTesting;
import com.google.gson.JsonElement;
import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.grpc.Internal;
import io.pulumi.Log;
import io.pulumi.core.AssetOrArchive;
import io.pulumi.core.Either;
import io.pulumi.core.internal.CompletableFutures;
import io.pulumi.core.internal.Constants;
import io.pulumi.core.internal.InputOutputData;
import io.pulumi.core.internal.UntypedInputOutput;
import io.pulumi.resources.ComponentResource;
import io.pulumi.resources.CustomResource;
import io.pulumi.resources.InputArgs;
import io.pulumi.resources.Resource;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Serializer {
    public final Set<Resource> dependentResources;

    private final boolean excessiveDebugOutput;

    public Serializer(boolean excessiveDebugOutput) {
        this.dependentResources = new HashSet<>();
        this.excessiveDebugOutput = excessiveDebugOutput;
    }

    /**
     * Takes in an arbitrary object and serializes it into a uniform form that can converted
     * trivially to a protobuf to be passed to the Pulumi engine.
     * <p>
     * The allowed 'basis' forms that can be serialized are:
     * <ol>
     * <li>{@code null}</li>
     * <li>{@code bool}</li>
     * <li>{@code int}</li>
     * <li>{@code double}</li>
     * <li>{@code String}</li>
     * <li>@see {@link io.pulumi.core.Asset}</li>
     * <li>@see {@link io.pulumi.core.Archive}</li>
     * <li>@see {@link Resource}</li>
     * <li>@see {@link io.pulumi.resources.ResourceArgs}</li>
     * <li>@see {@link com.google.gson.JsonElement}</li>
     * </ol>
     * Additionally, other more complex objects can be serialized as long as they are built
     * out of serializable objects.
     * These complex objects include:
     * <ol>
     * <li>@see {@link io.pulumi.core.Input{T}}. As long as they are an Input of a serializable type.</li>
     * <li>@see {@link io.pulumi.core.Output{T}}. As long as they are an Output of a serializable type.</li>
     * <li>@see {@link java.util.List}. As long as all elements in the list are serializable.</li>
     * <li>@see {@link java.util.Map}. As long as the key of the dictionary are {@code String} and as {@code long} as the value are all serializable.</li>
     * </ol>
     * No other forms are allowed.
     * <p/>
     * <p>
     * This function will only return values of a very specific shape. Specifically, the
     * result values returned will <b>only</b> be one of:
     * <p/>
     * <ol>
     * <li>{@code null}</li>
     * <li>{@code bool}</li>
     * <li>{@code int}</li>
     * <li>{@code double}</li>
     * <li><{@code String}</li>
     * <li>An {@link java.util.List} containing only these result value types.</li>
     * <li>An {@link java.util.Map} where the keys are {@code String}s and the values are only these result value types.</li>
     * </ol>
     * No other result type are allowed to be returned.
     */
    public CompletableFuture</* @Nullable */ Object> serializeAsync(String ctx, @Nullable Object prop, boolean keepResources) {
        // IMPORTANT:
        // IMPORTANT: Keep this in sync with serializesPropertiesSync in invoke.ts
        // IMPORTANT:
        if (prop == null ||
                prop instanceof Boolean ||
                prop instanceof Integer ||
                prop instanceof Double ||
                prop instanceof String) {
            if (excessiveDebugOutput) {
                Log.debug(String.format("Serialize property[%s]: primitive=%s", ctx, prop));
            }
            return CompletableFuture.completedFuture(/* @Nullable */ prop);
        }

        if (prop instanceof Optional) {
            var optional = (Optional<Object>) prop;
            if (excessiveDebugOutput) {
                Log.debug(String.format("Serialize property[%s]: Recursing into Optional", ctx));
            }

            return serializeAsync(ctx, optional.orElse(null), keepResources);
        }

        if (prop instanceof InputArgs) {
            var args = (InputArgs) prop;
            return serializeInputArgsAsync(ctx, args, keepResources).thenApply(m -> (Object) m);
        }

        if (prop instanceof AssetOrArchive) {
            var assetOrArchive = (AssetOrArchive) prop;
            return serializeAssetOrArchiveAsync(ctx, assetOrArchive, keepResources).thenApply(m -> (Object) m);
        }

        if (prop instanceof CompletableFuture) {
            throw new IllegalArgumentException(
                    String.format("Tasks are not allowed inside ResourceArgs. Please wrap your Task in an Output:\n\t%s", ctx));
        }

        if (prop instanceof Either) {
            var either = (Either<Object, Object>) prop;
            if (excessiveDebugOutput) {
                Log.debug(String.format("Serialize property[%s]: Recursing into Either", ctx));
            }

            return serializeAsync(ctx, either.internalUntypedLeftOrRight(), keepResources);
        }

        if (prop instanceof JsonElement) {
            var element = (JsonElement) prop;
            if (excessiveDebugOutput) {
                Log.debug(String.format("Serialize property[%s]: Recursing into JsonElement", ctx));
            }

            return CompletableFuture.completedFuture(serializeJson(ctx, element));
        }

        if (prop instanceof UntypedInputOutput) {
            var inputOutput = (UntypedInputOutput) prop;
            if (excessiveDebugOutput) {
                Log.debug(String.format("Serialize property[%s]: Recursing into UntypedInputOutput", ctx));
            }

            return inputOutput.internalGetDataUntypedAsync().thenCompose(
                    (InputOutputData<Object> data) -> {
                        this.dependentResources.addAll(data.getResources());

                        // When serializing an InputOutput, we will either serialize it as its resolved value
                        // or the "unknown value" sentinel.
                        // We will do the former for all outputs created directly by user code (such outputs always
                        // resolve isKnown to true) and for any resource outputs that were resolved with known values.
                        var isKnown = data.isKnown();
                        var isSecret = data.isSecret();

                        if (!isKnown) {
                            return CompletableFuture.completedFuture(Constants.UnknownValue);
                        }

                        if (isSecret) {
                            return serializeAsync(String.format("%s.id", ctx), data.getValue(), keepResources).thenApply(
                                    /* @Nullable */ value -> {
                                        var result = new HashMap<String, /* @Nullable */ Object>();
                                        result.put(Constants.SpecialSigKey, Constants.SpecialSecretSig);
                                        result.put(Constants.SecretValueName, value);
                                        return result;
                                    }
                            );
                        }
                        return serializeAsync(String.format("%s.id", ctx), data.getValue(), keepResources);
                    }
            );
        }

        if (prop instanceof CustomResource) {
            var customResource = (CustomResource) prop;
            // Resources aren't serializable; instead, we serialize them as references to the ID property.
            if (excessiveDebugOutput) {
                Log.debug(String.format("Serialize property[%s]: Encountered CustomResource", ctx));
            }

            this.dependentResources.add(customResource);

            return serializeAsync(String.format("%s.id", ctx), customResource.getId(), keepResources).thenCompose(
                    /* @Nullable */ id -> {
                        if (keepResources) {
                            return serializeAsync(String.format("%s.urn", ctx), customResource.getId(), keepResources).thenApply(
                                    /* @Nullable */ urn -> {
                                        var result = new HashMap<String, /* @Nullable */ Object>();
                                        result.put(Constants.SpecialSigKey, Constants.SpecialResourceSig);
                                        result.put(Constants.ResourceUrnName, urn);
                                        result.put(Constants.ResourceIdName, Objects.equals(id, Constants.UnknownValue) ? "" : id);
                                        return result;
                                    }
                            );
                        }
                        return CompletableFuture.completedFuture(id);
                    }
            );
        }

        if (prop instanceof ComponentResource) {
            var componentResource = (ComponentResource) prop;
            // Component resources often can contain cycles in them. For example, an awsinfra
            // SecurityGroupRule can point a the awsinfra SecurityGroup, which in turn can point
            // back to its rules through its 'egressRules' and 'ingressRules' properties.
            // If serializing out the 'SecurityGroup' resource ends up trying to serialize out
            // those properties, a deadlock will happen, due to waiting on the child, which is
            // waiting on the parent.
            //
            // Practically, there is no need to actually serialize out a component.  It doesn't
            // represent a real resource, nor does it have normal properties that need to be
            // tracked for differences (since changes to its properties don't represent changes
            // to resources in the real world).
            //
            // So, to avoid these problems, while allowing a flexible and simple programming
            // model, we just serialize out the component as its urn.  This allows the component
            // to be identified and tracked in a reasonable manner, while not causing us to
            // compute or embed information about it that is not needed, and which can lead to
            // deadlocks.
            if (excessiveDebugOutput) {
                Log.debug(String.format("Serialize property[%s]: Encountered ComponentResource", ctx));
            }

            return serializeAsync(String.format("%s.urn", ctx), componentResource.getUrn(), keepResources).thenApply(
                    /* @Nullable */ urn -> {
                        if (keepResources) {
                            var result = new HashMap<String, /* @Nullable */ Object>();
                            result.put(Constants.SpecialSigKey, Constants.SpecialResourceSig);
                            result.put(Constants.ResourceUrnName, urn);
                            return result;
                        }
                        return urn;
                    }
            );
        }

        if (prop instanceof Map) {
            var map = (Map<Object, /* @Nullable */ Object>) prop;
            return serializeMapAsync(ctx, map, keepResources).thenApply(m -> (Object) m);
        }

        if (prop instanceof List) {
            var list = (List</* @Nullable */ Object>) prop;
            return serializeListAsync(ctx, list, keepResources).thenApply(l -> (Object) l);
        }

        if (prop instanceof Enum /*&& e.GetTypeCode() == TypeCode.Int32*/) {
//            return (int)prop;
            throw new UnsupportedOperationException("unimplemented"); // FIXME
        }

        var propType = prop.getClass();
        /*if (propType.IsValueType && propType.GetCustomAttribute<EnumTypeAttribute>() != null)
        {
            var mi = propType.GetMethod("op_Explicit", BindingFlags.Public | BindingFlags.Static, null, new[] { propType }, null);
            if (mi == null || (mi.ReturnType != typeof(string) && mi.ReturnType != typeof(double)))
            {
                throw new InvalidOperationException($"Expected {propType.FullName} to have an explicit conversion operator to String or Double.\n\t{ctx}");
            }
            return mi.Invoke(null, new[] { prop });
        }*/ // TODO

        throw new UnsupportedOperationException(
                String.format("Type '%s' is not a supported argument type.\n\t%s", propType.getSimpleName(), ctx)
        );
    }

    @Nullable
    @VisibleForTesting
    @Internal
    Object serializeJson(String ctx, JsonElement element) {
        Objects.requireNonNull(ctx);
        Objects.requireNonNull(element);

        if (element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            var primitive = element.getAsJsonPrimitive();
            if (primitive.isString()) {
                return primitive.getAsString();
            }
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }
            if (primitive.isNumber()) {
                return primitive.getAsNumber();
            }
            throw new UnsupportedOperationException(String.format("unsupported JSON primitive type: '%s'", primitive));
        }
        if (element.isJsonArray()) {
            var array = element.getAsJsonArray();
            var result = new ArrayList</* @Nullable */ Object>(array.size());
            var index = 0;
            for (var child : array) {
                result.add(serializeJson(String.format("%s[%s]", ctx, index), child));
                index++;
            }
            return result; // can contain null elements!
        }
        if (element.isJsonObject()) {
            var object = element.getAsJsonObject();
            var result = new HashMap<String, /* @Nullable */ Object>();
            for (var entry : object.entrySet()) {
                result.put(entry.getKey(), serializeJson(String.format("%s.%s", ctx, entry.getKey()), entry.getValue()));
            }
            return result; // can contain null values!
        }
        throw new UnsupportedOperationException(
                String.format("Unknown JsonElement: '%s'", element));
    }

    private CompletableFuture<Map<String, /* @Nullable */ Object>> serializeAssetOrArchiveAsync(String ctx, AssetOrArchive assetOrArchive, boolean keepResources) {
        if (excessiveDebugOutput) {
            Log.debug(String.format("Serialize property[%s]: asset/archive=%s", ctx, assetOrArchive.getClass().getSimpleName()));
        }

        var propName = assetOrArchive.getPropName();
        return serializeAsync(ctx + "." + propName, assetOrArchive.getValue(), keepResources).thenApply(
                /* @Nullable */ value -> {
                    var result = new HashMap<String, /* @Nullable */ Object>();
                    result.put(Constants.SpecialSigKey, assetOrArchive.getSigKey());
                    result.put(assetOrArchive.getPropName(), value);
                    return result;
                }
        );
    }

    private CompletableFuture<Map<String, /* @Nullable */ Object>> serializeInputArgsAsync(String ctx, InputArgs args, boolean keepResources) {
        if (excessiveDebugOutput) {
            Log.debug(String.format("Serialize property[%s]: Recursing into ResourceArgs", ctx));
        }

        return args.internalUntypedNullableToMapAsync().thenCompose(
                map -> serializeMapAsync(ctx, map, keepResources)
        );
    }

    private CompletableFuture<ArrayList</* @Nullable */ Object>> serializeListAsync(String ctx, List</* @Nullable */ Object> list, boolean keepResources) {
        if (excessiveDebugOutput) {
            Log.debug(String.format("Serialize property[%s]: Hit list", ctx));
        }

        var resultFutures = new ArrayList<CompletableFuture</* @Nullable */ Object>>(list.size());
        for (int i = 0, n = list.size(); i < n; i++) {
            if (excessiveDebugOutput) {
                Log.debug(String.format("Serialize property[%s]: array[%d] element", ctx, i));
            }

            resultFutures.add(serializeAsync(String.format("%s[%s]", ctx, i), list.get(i), keepResources));
        }

        return CompletableFutures.allOf(resultFutures).thenApply(
                completedFutures -> {
                    var results = new ArrayList</* @Nullable */ Object>(completedFutures.size());
                    for (var future : completedFutures) {
                        results.add(future.join());
                    }
                    return results;
                }
        );
    }

    private CompletableFuture<Map<String, /* @Nullable */ Object>> serializeMapAsync(String ctx, Map<Object, /* @Nullable */ Object> map, boolean keepResources) {
        if (excessiveDebugOutput) {
            Log.debug(String.format("Serialize property[%s]: Hit dictionary", ctx));
        }

        var resultFutures = new HashMap<String, CompletableFuture</* @Nullable */ Object>>();
        for (var key : map.keySet()) {
            if (!(key instanceof String)) {
                throw new UnsupportedOperationException(
                        String.format("Dictionaries are only supported with string keys:\n\t%s", ctx));
            }
            var stringKey = (String) key;

            if (excessiveDebugOutput) {
                Log.debug(String.format("Serialize property[%s]: object.%s", ctx, stringKey));
            }

            // When serializing an object, we omit any keys with null values. This matches
            // JSON semantics.
            resultFutures.put(stringKey, serializeAsync(String.format("%s.%s", ctx, stringKey), map.get(stringKey), keepResources));
        }

        return CompletableFutures.allOf(resultFutures).thenApply(
                completedFutures -> {
                    var results = new HashMap<String, /* @Nullable */ Object>();
                    for (var entry : completedFutures.entrySet()) {
                        var key = entry.getKey();
                        var value = /* @Nullable */ entry.getValue().join();
                        // We treat properties with null values as if they do not exist.
                        if (value != null) {
                            results.put(key, value);
                        }
                    }
                    return results;
                }
        );
    }

    /**
     * Given a @see {@link Map} produced by @see #serializeAsync
     * produces the equivalent @see {@link Struct} that can be passed to the Pulumi engine.
     */
    public static Struct createStruct(Map<String, Object> serializedMap) {
        var result = Struct.newBuilder();
        for (var key : serializedMap.keySet()) { // TODO: C# had '.OrderBy(k => k)' here, what does it do?
            result.putFields(key, createValue(serializedMap.get(key)));
        }
        return result.build();
    }

    /**
     * Internal for testing purposes.
     */
    @Internal
    @VisibleForTesting
    static Value createValue(@Nullable Object value) {
        var builder = Value.newBuilder();
        if (value == null) {
            return builder.setNullValue(NullValue.NULL_VALUE).build();
        }
        if (value instanceof Integer) {
            return builder.setNumberValue(((Integer) value)).build();
        }
        if (value instanceof Double) {
            return builder.setNumberValue(((Double) value)).build();
        }
        if (value instanceof Boolean) {
            return builder.setBoolValue((Boolean) value).build();
        }
        if (value instanceof String) {
            return builder.setStringValue((String) value).build();
        }
        if (value instanceof List) {
            List<Value> values = ((List<Object>) value).stream()
                    .map(Serializer::createValue)
                    .collect(Collectors.<Value>toList());
            return builder.setListValue(ListValue.newBuilder().addAllValues(values)).build();
        }
        if (value instanceof Map) {
            Function<Object, String> keyCollector = (Object key) -> {
                if (key instanceof String) {
                    return (String) key;
                } else {
                    throw new UnsupportedOperationException("Expected a 'String' key, got: " + key.getClass().getCanonicalName());
                }
            };
            Function</*@Nullable*/ Object, /*@Nullable*/ Object> valueCollector = (/*@Nullable*/ Object v) -> v;
            Map<String, Object> map = ((Map<Object, Object>) value).entrySet().stream().collect(
                    Collectors.toMap(
                            keyCollector.compose(Map.Entry::getKey),
                            valueCollector.compose(Map.Entry::getValue)
                    )
            );
            return builder.setStructValue(createStruct(map)).build();
        }
        throw new UnsupportedOperationException("Unsupported value when converting to protobuf: " + value.getClass().getSimpleName());
    }
}
