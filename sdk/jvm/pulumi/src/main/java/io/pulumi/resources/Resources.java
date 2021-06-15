package io.pulumi.resources;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class Resources {
    private Resources() {
        throw new UnsupportedOperationException();
    }

    @Nullable
    protected static <T> List<T> mergeNullableList(@Nullable List<T> original, @Nullable List<T> additional) {
        if (original == null && additional == null) {
            return null;
        }
        if (original != null && additional == null) {
            return original;
        }
        //noinspection ConstantConditions
        if (original == null && additional != null) {
            return additional;
        }
        var list = new ArrayList<>(original);
        list.addAll(additional);
        return list;
    }

    protected static <T> List<T> copyNullableList(@Nullable List<T> original) {
        if (original == null) {
            return null;
        }
        return List.copyOf(original);
    }

}
