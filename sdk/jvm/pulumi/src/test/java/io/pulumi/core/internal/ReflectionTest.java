package io.pulumi.core.internal;

import com.google.gson.Gson;
import io.pulumi.core.internal.Reflection.TypeShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nullable;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class ReflectionTest {

    @Test
    void testTypeShapeToGSONConversion() {
        var token = TypeShape.list(Integer.class).toGSON().getType();
        var string = "[1,2,3,4]";

        var gson = new Gson();
        List<Integer> result = gson.fromJson(string, token);

        assertThat(result).isNotNull();
        assertThat(result).containsExactly(1, 2, 3, 4);
    }

    @SuppressWarnings("unused")
    private static Stream<Arguments> testDefaultValue() {
        return Stream.of(
                arguments(boolean.class, false, Boolean.class),
                arguments(Object.class, null, Object.class)
        );
    }

    @ParameterizedTest
    @MethodSource
    void testDefaultValue(Class<?> targetType, @Nullable Object expected, Class<?> resultType) {
        assertThat(Reflection.defaultValue(targetType))
                .isEqualTo(expected)
                .isInstanceOf(resultType);
    }
}