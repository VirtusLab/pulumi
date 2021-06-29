package io.pulumi.core;

import com.google.common.collect.ImmutableList;

public class InputList<T> extends Input<ImmutableList<T>> {
    public InputList() {
        this(Output.create(ImmutableList.of()));
    }
    public InputList(Output<ImmutableList<T>> values) {
        super(values);
    }
    public InputList<T> clone() {
        return new InputList<T>(outputValue);
    }
    
}
