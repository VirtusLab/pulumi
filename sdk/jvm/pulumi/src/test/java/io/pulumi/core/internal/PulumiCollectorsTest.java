package io.pulumi.core.internal;

import org.assertj.core.util.Lists;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static io.pulumi.core.internal.PulumiCollectors.toSingleton;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class PulumiCollectorsTest {

    @SuppressWarnings({"unused", "RedundantOperationOnEmptyContainer"})
    private static Stream<Arguments> testToSingletonFail() {
        return Stream.of(
                arguments("Expected a single element, got: empty or null", Lists.newArrayList((Object) null).stream()),
                arguments("Expected a single element, got: empty or null", Arrays.stream(new Object[]{null})),
                arguments("Expected a single element, got: empty or null", List.of().stream()),
                arguments("Expected a single element, got: empty or null", Arrays.stream(new Object[]{})),
                arguments("Expected a single element, got: multiple", List.of(1, 2).stream()),
                arguments("Expected a single element, got: multiple", Arrays.stream(new Boolean[]{true, false})),
                arguments("Expected a single element, got: multiple", List.of(1.0, 2.0, 3.0).stream()),
                arguments("Expected a single element, got: multiple", Arrays.stream(new Character[]{'a', 'b', 'c'}))
        );
    }

    @ParameterizedTest
    @MethodSource
    void testToSingletonFail(String message, Stream<?> stream) {
        //noinspection ResultOfMethodCallIgnored
        assertThatThrownBy(() -> stream.collect(toSingleton()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    @SuppressWarnings("unused")
    private static Stream<Arguments> testToSingletonSuccess() {
        return Stream.of(
                arguments(1, List.of(1).stream()),
                arguments(1, Arrays.stream(new Integer[]{1})),
                arguments("test", List.of("test").stream()),
                arguments("test", Arrays.stream(new String[]{"test"}))
        );
    }

    @ParameterizedTest
    @MethodSource
    void testToSingletonSuccess(Object expected, Stream<?> stream) {
        assertThat((Object) stream.collect(toSingleton())).isEqualTo(expected);
    }
}