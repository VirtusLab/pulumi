package io.pulumi.resources;

import java.util.ArrayList;
import java.util.Optional;

public final class CustomResourceOptions extends ResourceOptions {
    public static final CustomResourceOptions EMPTY = new CustomResourceOptions();

    public Optional<Boolean> deleteBeforeReplace;
    private Optional<ArrayList<String>> additionalSecretOutput;
    public ArrayList<String> getAdditionalSecretOutput() {
        return (!additionalSecretOutput.isEmpty()) ? new ArrayList<String>() : additionalSecretOutput.get();
    }
    public void setAdditionalSecretOutput(ArrayList<String> list) {
        if (list == null) {
            additionalSecretOutput = Optional.empty();
        } else {
            additionalSecretOutput = Optional.of(list);
        }
    }
    public Optional<String> ImportId;
    public CustomResourceOptions clone() {
        CustomResourceOptions clone = new CustomResourceOptions();
        clone.copyValues(this);
        clone.additionalSecretOutput = this.additionalSecretOutput;
        clone.deleteBeforeReplace = this.deleteBeforeReplace;
        clone.ImportId = this.ImportId;
        return clone;
    }
}
