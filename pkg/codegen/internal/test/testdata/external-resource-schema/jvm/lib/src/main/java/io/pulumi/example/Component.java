// *** WARNING: this file was generated by test. ***
// *** Do not edit by hand unless you're certain you know what you are doing! ***

package io.pulumi.example;
public class Component extends io.pulumi.CustomResource {
    public io.pulumi.Output<java.util.Optional<io.pulumi.pulumi.Kubernetes>> provider;
    public io.pulumi.Output<io.pulumi.aws.ec2.SecurityGroup> securityGroup;
    public io.pulumi.Output<java.util.Optional<java.util.Map<String, io.pulumi.kubernetes.storage.k8s.io/v1.StorageClass>>> storageClasses;

    /**    * Create a Component resource with the given name, arguments, and options.
    * @param name the unique name of the resource
    * @param args The arguments used to populate this resource's properties
    * @param options A bag of options that control this resource's behavior
    */
    public Component(String name, ComponentArgs args, @javax.annotation.Nullable io.pulumi.CustomResourceOptions options) {
        super("example::Component", name, args, makeResourceOptions(options, io.pulumi.Input.create("")));
    }

    private Component(String name, io.pulumi.Input<String> id, @javax.annotation.Nullable io.pulumi.CustomResourceOptions options) {
        super("example::Component", name, null, makeResourceOptions(options, id));
    }

    private static io.pulumi.CustomResourceOptions makeResourceOptions(@javax.annotation.Nullable io.pulumi.CustomResourceOptions options, io.pulumi.Input<String> id) {
        io.pulumi.CustomResourceOptions defaultOptions = new io.pulumi.CustomResourceOptions();
        defaultOptions.Version = Utilities.Version;
        io.pulumi.CustomResourceOptions merged = io.pulumi.CustomResourceOptions.merge(defaultOptions, options);
        merged.Id = id;
        return merged;
    }
    /**    * Get an existing Component resource's state with the given name, ID, and optional extra
    * properties used to qualify the lookup.
    * @param name The unique name of the resulting resource.
    * @param id The unique provider ID of the resource to lookup.
    * @param options A bag of options that control this resource's behavior.
    */
    public static Component get(String name, @javax.annotation.Nullable String id, io.pulumi.CustomResourceOptions options) {
        return new Component(name, io.pulumi.Input.create(id), options);
    }
}
