// *** WARNING: this file was generated by test. ***
// *** Do not edit by hand unless you're certain you know what you are doing! ***

package io.pulumi.example.inputs;

    public class ArgFunctionArgs extends io.pulumi.InvokeArgs {
        @io.pulumi.serialization.InputAttribute(isRequired = false)
        private java.util.Optional<io.pulumi.random.RandomPet> name = java.util.Optional.empty();
        public io.pulumi.random.RandomPet getName() {
           return name.isPresent() ? name.get() : null;
        }
        public ArgFunctionArgs setName(io.pulumi.random.RandomPet name) {
            this.name = name != null ? java.util.Optional.of(name) : java.util.Optional.empty();
            return this;
        }

        public ArgFunctionArgs(

            ) {
    }
}
