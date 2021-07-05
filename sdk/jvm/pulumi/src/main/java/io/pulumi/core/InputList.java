package io.pulumi.core;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.List;

public final class InputList<T> extends Input<List<T>> implements Iterable<T> {
    public InputList() {
        this(Outputs.create(List.of()));
    }

    private InputList(Output<List<T>> values) {
        super(values);
    }

    public InputList<T> concat(InputList<T> other) {
        return new InputList<>(Outputs.internalConcat(this.outputValue, other.outputValue));
    }

    @Nonnull
    @Override
    public Iterator<T> iterator() { // TODO: how do we make async iterator?
        return new Iterator<>() {
            @Nullable
            private Iterator<T> joined;

            private Iterator<T> getOrJoin() {
                if (this.joined == null) {
                    this.joined = InputList.this.toOutput().internalGetValueAsync().join().iterator();
                }
                return this.joined;
            }

            @Override
            public boolean hasNext() {
                return getOrJoin().hasNext();
            }

            @Override
            public T next() {
                return getOrJoin().next();
            }
        };
    }
}