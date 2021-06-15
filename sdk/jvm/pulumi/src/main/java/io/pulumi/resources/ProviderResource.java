package io.pulumi.resources;

import com.google.common.base.Strings;
import io.grpc.Internal;
import io.pulumi.core.internal.Constants;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.concurrent.CompletableFuture;

/**
 * A @see {@link Resource} that implements CRUD operations
 * for other custom resources. These resources are managed similarly to other resources,
 * including the usual diffing and update semantics.
 */
@ParametersAreNonnullByDefault
public class ProviderResource extends CustomResource {
    private static final String ProviderResourceTypePrefix = "pulumi:providers:";
    private final String aPackage;
    @Nullable
    private CompletableFuture<String> registrationId;

    /**
     * Creates and registers a new provider resource for a particular package.
     *
     * @param aPackage The package associated with this provider
     * @param name     The unique name of the provider
     * @param args     The configuration to use for this provider
     * @param options  A bag of options that control this provider's behavior
     */
    public ProviderResource(String aPackage, String name, ResourceArgs args, @Nullable CustomResourceOptions options) {
        this(aPackage, name, args, options, false);
    }

    /**
     * Creates and registers a new provider resource for a particular package.
     *
     * @param aPackage   The package associated with this provider
     * @param name       The unique name of the provider
     * @param args       The configuration to use for this provider
     * @param options    A bag of options that control this provider's behavior
     * @param dependency True if this is a synthetic resource used internally for dependency tracking
     */
    protected ProviderResource(String aPackage, String name,
                               ResourceArgs args, @Nullable CustomResourceOptions options, boolean dependency) {
        super(internalProviderResourceType(aPackage), name, args, options, dependency);
        this.aPackage = aPackage;
    }

    @Internal
    String internalGetPackage() {
        return aPackage;
    }

    protected static String internalProviderResourceType(String aPackage) {
        return ProviderResourceTypePrefix + aPackage;
    }

    /*public ProviderResource copy() {
        return copy(this);
    }

    public static ProviderResource copy(ProviderResource original) {
        return new ProviderResource(
                original.getPackage(),
                original.getName(),
                original.getOptions() // TODO
        );
    }*/

    // TODO: why is this method needed? looks hacky, how to make it better? and why is this async?
    //       there is a mutable "cache" field 'provider.registrationId' also
    public static CompletableFuture<String> internalRegisterAsync(@Nullable ProviderResource provider) {
        if (provider == null) {
            return CompletableFuture.supplyAsync(() -> null);
        }

        if (provider.registrationId == null) { // TODO: this caching, is it needed really?
            var providerUrn = provider.getUrn().internalGetValueAsync();
            var providerId = provider.getId().internalGetValueAsync()
                    .thenApply(
                            pId -> Strings.isNullOrEmpty(pId)
                                    ? Constants.UnknownValue
                                    : pId
                    );

            provider.registrationId = providerUrn.thenCompose(
                    pUrn -> providerId.thenApply(
                            pId -> String.format("%s::%s", pUrn, pId)
                    ));
        }
        return provider.registrationId;
    }
}
