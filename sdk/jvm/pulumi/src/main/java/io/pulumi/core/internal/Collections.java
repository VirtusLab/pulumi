package io.pulumi.core.internal;

import io.grpc.Internal;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Internal
public class Collections {
    private Collections() {
        throw new UnsupportedOperationException();
    }

    public static <K, V> Map<Object, /* @Nullable */ Object> typedOptionalMapToUntypedNullableMap(Map<K, Optional<V>> map) {
        Objects.requireNonNull(map);
        return map.entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public static <K, V> Map<K, Optional<V>> untypedNullableMapToTypedOptionalMap(
            Map<Object, /* @Nullable */ Object> map,
            Function<Object, K> typedKey,
            Function</* @Nullable */ Object, Optional<V>> typedValue
    ) {
        Objects.requireNonNull(map);
        return map.entrySet()
                .stream()
                .collect(Collectors.toMap(typedKey, typedValue));
    }
}
