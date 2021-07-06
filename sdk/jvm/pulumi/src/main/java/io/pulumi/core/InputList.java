package io.pulumi.core;

import io.pulumi.core.internal.InputOutputData;
import io.pulumi.core.internal.TypedInputOutput;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public final class InputList<T> extends InputImpl<List<T>, Input<List<T>>> implements Input<List<T>>, Iterable<T> {

    protected InputList() {
        this(List.of());
    }

    protected InputList(List<T> values) {
        this(InputOutputData.ofAsync(CompletableFuture.completedFuture(values), false));
    }

    protected InputList(Input<List<T>> inputs) {
        this(((TypedInputOutput<List<T>>) inputs).internalGetDataAsync());
    }

    protected InputList(CompletableFuture<InputOutputData<List<T>>> values) {
        super(values);
    }

    protected InputList<T> newInstance(CompletableFuture<InputOutputData<List<T>>> dataFuture) {
        return new InputList<>(dataFuture);
    }

    @Override
    public <U> Input<U> applyInput(Function<List<T>, Input<U>> func) {
        return new InputDefault<>(InputOutputData.apply(dataFuture, func.andThen(
                o -> ((TypedInputOutput<U>) o).internalGetDataAsync())
        ));
    }

    @Override
    public InputList<T> copy() {
        return new InputList<>(this.dataFuture.copy());
    }

    public InputList<T> concat(InputList<T> other) {
        Objects.requireNonNull(other);

        return new InputList<>(
                Input.tuple(this, other).apply(
                        t -> Stream
                                .concat(t.t1.stream(), t.t2.stream())
                                .collect(Collectors.<T>toList())
                )
        );
    }

    @Nonnull
    @Override
    public Iterator<T> iterator() { // TODO: how do we make async iterator?
        return new Iterator<>() {
            @Nullable
            private Iterator<T> joined;

            private Iterator<T> getOrJoin() {
                if (this.joined == null) {
                    this.joined = InputList.this.internalGetValueAsync().join().iterator();
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

    public Stream<T> stream() {
        return StreamSupport.stream(spliterator(), false);
    }

    // Static section -----
    public static <T> InputList<T> of(List<T> values) {
        return new InputList<>(values);
    }

    public static <T> InputList<T> empty() {
        return new InputList<>();
    }
}