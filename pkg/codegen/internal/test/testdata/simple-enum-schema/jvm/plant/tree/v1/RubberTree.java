// *** WARNING: this file was generated by test. ***
// *** Do not edit by hand unless you're certain you know what you are doing! ***

package io.pulumi.Plant.Tree.V1;
    public class RubberTree extends Pulumi.CustomResource {
        public Output<Optional<io.pulumiPlant.Outputs.Container>> container;
        public Output<io.pulumiPlant.Tree.V1.Diameter> diameter;
        public Output<Optional<String>> farm;
        public Output<Optional<io.pulumiPlant.Tree.V1.TreeSize>> size;
        public Output<io.pulumiPlant.Tree.V1.RubberTreeVariety> type;

        /**        * Create a RubberTree resource with the given name, arguments, and options.
        * @param name the unique name of the resource
        * @param args The arguments used to populate this resource's properties
        * @param options A bag of options that control this resource's behavior
        */
        public RubberTree(string name, RubberTreeArgs args, Optional<CustomResourceOptions> options) {
            super("plant:tree/v1:RubberTree", name, args.isPresent()?args.get(): new RubberTreeArgs(), makeResourceOptions(options, ""));
        }

        private static CustomResourceOptions makeResourceOptions(CustomResourceOptions options, Optional<Input<String>> id) {
            CustomResourceOptions defaultOptions = new CustomResourceOptions();
            defaultOptions.Verion = Utilities.Version;
            CustomResourceOptions merged = CustomResourceOptions.merge(defaultOptions, options);
            merged.Id = id.isPresent() ? id.get() : merged.Id;
            return merged;
        }
    }

    public final class RubberTreeArgs extends io.pulumi.ResourceArgs {
        private Optional<Input<io.pulumiPlant.Inputs.ContainerArgs>> container = Optional.empty();
        public Optional<Input<io.pulumiPlant.Inputs.ContainerArgs>> getContainer() {
           return container.isPresent() ? container.get() : new Optional<Input<io.pulumiPlant.Inputs.ContainerArgs>>();
        }
        public RubberTreeArgs setContainer(Optional<Input<io.pulumiPlant.Inputs.ContainerArgs>> container) {
            this.container = container != null ? Optional.of(container) : Optional.empty();
            return this;
        }

        private Optional<Input<io.pulumiPlant.Tree.V1.Diameter>> diameter = 6;
        public Input<io.pulumiPlant.Tree.V1.Diameter> getDiameter() {
           return diameter.isPresent() ? diameter.get() : new Input<io.pulumiPlant.Tree.V1.Diameter>();
        }
        public RubberTreeArgs setDiameter(Input<io.pulumiPlant.Tree.V1.Diameter> diameter) {
            this.diameter = diameter != null ? Optional.of(diameter) : Optional.empty();
            return this;
        }

        private Optional<InputUnion<io.pulumiPlant.Tree.V1.Farm, String>> farm = "(unknown)";
        public Optional<InputUnion<io.pulumiPlant.Tree.V1.Farm, String>> getFarm() {
           return farm.isPresent() ? farm.get() : new Optional<InputUnion<io.pulumiPlant.Tree.V1.Farm, String>>();
        }
        public RubberTreeArgs setFarm(Optional<InputUnion<io.pulumiPlant.Tree.V1.Farm, String>> farm) {
            this.farm = farm != null ? Optional.of(farm) : Optional.empty();
            return this;
        }

        private Optional<Input<io.pulumiPlant.Tree.V1.TreeSize>> size = "medium";
        public Optional<Input<io.pulumiPlant.Tree.V1.TreeSize>> getSize() {
           return size.isPresent() ? size.get() : new Optional<Input<io.pulumiPlant.Tree.V1.TreeSize>>();
        }
        public RubberTreeArgs setSize(Optional<Input<io.pulumiPlant.Tree.V1.TreeSize>> size) {
            this.size = size != null ? Optional.of(size) : Optional.empty();
            return this;
        }

        private Optional<Input<io.pulumiPlant.Tree.V1.RubberTreeVariety>> type = "Burgundy";
        public Input<io.pulumiPlant.Tree.V1.RubberTreeVariety> getType() {
           return type.isPresent() ? type.get() : new Input<io.pulumiPlant.Tree.V1.RubberTreeVariety>();
        }
        public RubberTreeArgs setType(Input<io.pulumiPlant.Tree.V1.RubberTreeVariety> type) {
            this.type = type != null ? Optional.of(type) : Optional.empty();
            return this;
        }

        public RubberTreeArgs(
            Optional<Input<io.pulumiPlant.Tree.V1.Diameter>> diameter,
            Optional<Input<io.pulumiPlant.Tree.V1.RubberTreeVariety>> type
            ) {
                this.diameter = diameter;
                this.type = type;
        }
        }
