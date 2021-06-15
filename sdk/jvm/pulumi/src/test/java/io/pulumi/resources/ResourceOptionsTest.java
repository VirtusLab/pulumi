package io.pulumi.resources;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nullable;
import java.util.List;
import java.util.stream.Stream;

import static io.pulumi.resources.Resources.mergeNullableList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class ResourceOptionsTest {

    @SuppressWarnings("unused")
    private static Stream<Arguments> testMergeNullableList() {
        return Stream.of(
                arguments(null, null, null),
                arguments(null, List.of(), List.of()),
                arguments(List.of(), null, List.of()),
                arguments(List.of(), List.of(), List.of()),
                arguments(List.of("a"), List.of("b"), List.of("a", "b"))
        );
    }

    @ParameterizedTest
    @MethodSource
    void testMergeNullableList(@Nullable List<String> left, @Nullable List<String> right, @Nullable List<String> expected) {
        assertEquals(expected, mergeNullableList(left, right));
    }
}