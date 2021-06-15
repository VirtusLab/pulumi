package io.pulumi.core;

import javax.annotation.Nonnull;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;
import java.util.function.Consumer;

public final class InputList<T> extends Input<List<T>> implements Iterable<T> {
    public InputList() {
        this(Outputs.create(List.of()));
    }

    private InputList(Output<List<T>> values) {
        super(values);
    }

    public InputList<T> copy() {
        return new InputList<>(this.outputValue.copy());
    }

    public InputList<T> concat(InputList<T> other) {
        return new InputList<>(Outputs.internalConcat(this.outputValue, other.outputValue));
    }

    @Nonnull
    @Override
    public Iterator<T> iterator() {
        return this.toOutput().internalGetValueAsync().join().iterator(); // TODO: how do we make async iterator?
    }

    @Override
    public void forEach(Consumer<? super T> action) {
        Iterable.super.forEach(action);
    }

    @Override
    public Spliterator<T> spliterator() {
        return Iterable.super.spliterator();
    }
}