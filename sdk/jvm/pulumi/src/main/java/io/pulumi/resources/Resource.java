package io.pulumi.resources;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import io.grpc.internal.BackoffPolicy.Provider;
import io.pulumi.Stack;
import io.pulumi.core.Alias;
import io.pulumi.core.Input;
import io.pulumi.core.Output;
import io.pulumi.core.Urn;
import io.pulumi.deployment.Deployment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


public class Resource {
    private final String type;
    private final String name;

    private final Optional<Resource> parent;

    private final Set<Resource> childResources = new HashSet<>();

    public final Output<String> urn;

    private final boolean protect;

    private final ImmutableList<ResourceTransformation> transformations;

    private final ImmutableList<Input<String>> aliases;

    public String getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    private final ImmutableMap<String, ProviderResource> providers;

    protected Resource(String type, String name, boolean custom,
                       ResourceArgs args, ResourceOptions options) {
        this(type, name, custom, args, options, false, false);
    }

    protected Resource(
            String type, String name, boolean custom,
            ResourceArgs args, ResourceOptions options,
            boolean remote, boolean dependency)
    {
        if (dependency) {
            this.type = "";
            this.name = "";
            this.protect = false;
            this.providers = ImmutableMap.of();
            return;
        }
        Objects.requireNonNull(type);
        Objects.requireNonNull(name);

        Optional<Resource> parent = type == null ? null : null; // TODO optional empty
        
        this.type = type;
        this.name = name;

        ArrayList<ResourceTransformation> transformationsMutable = Lists.newArrayList(options.getResourceTransformations());
        if (parent.isPresent()) {
            transformationsMutable.addAll(parent.get().transformations);
        }

        this.transformations = ImmutableList.copyOf(transformationsMutable);

        for (ResourceTransformation fun : transformations) {
            ResourceTransformationResult result = fun.apply(new ResourceTransformationArgs(this, args, options));
            if (result != null) {
                if (result.getOptions().parent != options.parent) {
                    throw new IllegalArgumentException("Transformations cannot currently be used to change the `parent` of a resource");
                }
                args = result.getArgs();
                options = result.getOptions();
            }
        }
        
        options = options.clone();
        Optional<ComponentResourceOptions> componentOpts = (options instanceof ComponentResourceOptions) ? Optional.of((ComponentResourceOptions) options) : Optional.<ComponentResourceOptions>empty();
        Optional<CustomResourceOptions> customOpts = (options instanceof CustomResourceOptions) ? Optional.of((CustomResourceOptions) options) : Optional.<CustomResourceOptions>empty();

        if (options.provider.isPresent() && componentOpts.isPresent() && componentOpts.get().getProviders().size() > 0) {
            throw new IllegalArgumentException("Do not supply both 'provider' and 'providers' options to a ComponentResource.");
        }

    this.parent = options.parent;
    HashMap<String, ProviderResource> providers =new HashMap<String, ProviderResource>();
    

    if (options.parent.isPresent()) {
        parent.get().childResources.add(this);

        if (options.protect.isEmpty()) {
            options.protect = Optional.of(parent.get().protect);
        }

        options.aliases = new ArrayList<Input<Alias>>(options.aliases);
        for (Input<String> parentAlias: options.parent.get().aliases) {
            options.aliases.add(Input.create(Urn.inheritedChildAlias(name, options.parent.get().getName(), parentAlias, type)));
        }
        providers.putAll(options.parent.get().providers);
    }
    if (custom) {
        Optional<ProviderResource> provider = options.provider;
        if (provider.isEmpty()) {
            if (options.parent.isPresent()) {
                options.provider = options.parent.get().getProvider(type, providers);
            }
        } else {
            String[] typeComponents = type.split(":");
            if (typeComponents.length == 3) {
                String pkg = typeComponents[0];
                providers.put(pkg, provider.get());
            }
        }
    } else {
        Optional<ImmutableList<ProviderResource>> providerList = options.provider.map(p -> ImmutableList.of(p)).or(() -> componentOpts.map(opt -> opt.getProviders()));
        providers.putAll(convertToProvidersMap(providerList));
        
    }

    this.providers = ImmutableMap.copyOf(providers);
    this.protect = options.protect.orElse(false);

    ArrayList<Input<String>> aliases = new ArrayList<Input<String>>();
    for (Input<Alias> alias : options.aliases) {
        aliases.add(Input.create(collapseAliasToUrn(alias, name, type, options.parent)));
    }
    this.aliases = ImmutableList.copyOf(aliases);

    Deployment.INSTANCE.readOrRegisterResource(this, remote, urn -> new DependencyResource(urn), args, options);
    }

    private static Output<String> collapseAliasToUrn(Input<Alias> alias, String defaultName, String defaultType, Optional<Resource> defaultParent) {
        return Output.create(alias).applyOutput(a -> {
            if (a.urn.isPresent()) {
                checkEmpty(a.name);
                checkEmpty(a.type);
                checkEmpty(a.project);
                checkEmpty(a.stack);
                checkEmpty(a.parent);
                checkEmpty(a.parentUrn);
                return Output.create(a.urn.get());
            }
            Input<String> name = a.name.orElse(Input.create(defaultName));
            Input<String> type = a.type.orElse(Input.create(defaultType));
            Input<String> project = a.project.orElse(Input.create(Deployment.INSTANCE.getProjectName()));
            Input<String> stack = a.stack.orElse(Input.create(Deployment.INSTANCE.getStackName()));

            Integer parentCount = (a.parent.isPresent()?1:0)+(a.parentUrn.isPresent()?1:0);
            if (parentCount >= 2) { throw new IllegalArgumentException("Specify either `parent` or `parentUrn`."); } // TODO: better msg

            if (name == null) {
                throw new IllegalArgumentException("No valid `name` passed in for alias");
            }

            if (type == null) {
                throw new IllegalArgumentException("No valid `type` passed in for alias");
            }
            // TODO: rethink alias: parent parenturn
            if (a.parent.isPresent()) {
                return Urn.create(name, type, a.parent.get(), Optional.of(project), Optional.of(stack));
            } else if (a.parentUrn.isPresent()) {
                return Urn.create(name, type, a.parentUrn.get(), Optional.of(project), Optional.of(stack));
           } else if (a.noParent) {
                return Urn.create(name, type, Optional.of(project), Optional.of(stack));
           } else if (defaultParent.isPresent()) {
                return Urn.create(name, type, defaultParent.get(), Optional.of(project), Optional.of(stack));
           } else {
                return Urn.create(name, type, Optional.of(project), Optional.of(stack));
           }
        });
    }
    private static <T> void checkEmpty(Optional<T> t) {
        if (t.isPresent()) {
            throw new IllegalArgumentException("Alias should not specify both urn and other."); // TODO: better msg
        }
    }
    private static ImmutableMap<String, ProviderResource> convertToProvidersMap(Optional<ImmutableList<ProviderResource>> providers) {
        HashMap<String, ProviderResource> result = new HashMap<String, ProviderResource>();
        if (providers.isPresent()) {
            for (ProviderResource provider : providers.get()) {
                result.put(provider.packageName, provider);
            }
        }
        return ImmutableMap.copyOf(result);
    }
    private Optional<ProviderResource> getProvider(String moduleMember, HashMap<String, ProviderResource> providers) {
        String[] memComponents = moduleMember.split(":");
        if (memComponents.length != 3) {
            return Optional.<ProviderResource>empty();
        } else {
            return providers.get(memComponents[0]) != null ? Optional.of(providers.get(memComponents[0])) : Optional.<ProviderResource>empty();
        }
    }
}
