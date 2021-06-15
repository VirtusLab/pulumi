package io.pulumi.resources;

import com.google.common.collect.ImmutableList;
import io.pulumi.core.internal.annotations.InputAttribute;

import java.util.Optional;
import java.util.function.Function;

/**
 * Base type for all input argument classes.
 */
public abstract class InputArgs {
    private final ImmutableList<InputInfo> inputInfos;

    protected abstract void validateMember(Class<?> memberType, String fullName);

    protected InputArgs() {
        // TODO
        throw new UnsupportedOperationException();
    }

    // TODO

    private static final class InputInfo {
        public final InputAttribute attribute;
        public final Class<?> memberType;
        public final String memberName;
        public final Function<Object, Optional<Object>> getValue;

        public InputInfo(
                InputAttribute attribute,
                String memberName, Class<?> memberType,
                Function<Object, Optional<Object>> getValue
        ) {
            this.attribute = attribute;
            this.memberName = memberName;
            this.memberType = memberType;
            this.getValue = getValue;
        }
    }
}
