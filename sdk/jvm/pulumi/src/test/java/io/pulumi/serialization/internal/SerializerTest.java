package io.pulumi.serialization.internal;

import io.pulumi.core.internal.InputOutputData;
import io.pulumi.deployment.DeploymentTests;
import io.pulumi.deployment.internal.EngineLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newHashMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class SerializerDeserializerTest {
    private static EngineLogger logger;

    @BeforeAll
    public static void mockSetup() {
        logger = DeploymentTests.setupDeploymentMocks();
    }

    @AfterEach
    public void printInternalErrorCount() {
        DeploymentTests.printErrorCount(logger);
    }

    @SuppressWarnings("unused")
    private static Stream<Arguments> testSerializeDeserializeCommonTypes() {
        return Stream.of(
                /*  1 */ arguments(null, (Consumer<Object>) o -> assertThat(o).isNull()),
                /*  2 */ arguments("test", (Consumer<Object>) o -> assertThat(o).isEqualTo("test")),
                /*  3 */ arguments(true, (Consumer<Object>) o -> assertThat(o).isEqualTo(true)),
                /*  4 */ arguments(false, (Consumer<Object>) o -> assertThat(o).isEqualTo(false)),
                /*  5 */ arguments(1, (Consumer<Object>) o -> assertThat(o).isEqualTo(1.0)),
                /*  6 */ arguments(1.0, (Consumer<Object>) o -> assertThat(o).isEqualTo(1.0)),
                /*  7 */ arguments(List.of(), (Consumer<Object>) o -> assertThat(o).isEqualTo(List.of())),
                /*  8 */ arguments(Map.of(), (Consumer<Object>) o -> assertThat(o).isEqualTo(Map.of())),
                /*  9 */ arguments(List.of(1), (Consumer<Object>) o -> assertThat(o).isEqualTo(List.of(Optional.of(1.0)))),
                /* 10 */ arguments(Map.of("1", 1), (Consumer<Object>) o -> assertThat(o).isEqualTo(Map.of("1", Optional.of(1.0)))),
                /* 11 */ arguments(newArrayList(1, null),
                        (Consumer<Object>) o -> assertThat(o).isEqualTo(List.of(Optional.of(1.0), Optional.empty()))),
                /* 12 */ arguments(((Supplier<HashMap<Object, Object>>) () -> {
                    var map = newHashMap();
                    map.put("1", null);
                    return map;
                }).get(), (Consumer<Object>) o -> assertThat(o).isEqualTo(Map.of())) // we remove null entries explicitly in serialization in serializeMapAsync
        );
    }

    @ParameterizedTest
    @MethodSource
    void testSerializeDeserializeCommonTypes(@Nullable Object given, Consumer<Object> expected) {
        var serialized =
                new Serializer(true)
                        .serializeAsync("", given, true);

        var deserialized = serialized
                .thenApply(Serializer::createValue)
                .thenApply(Deserializer::deserialize)
                .thenApply(InputOutputData::getValue)
                .join();

        expected.accept(deserialized);
    }
}