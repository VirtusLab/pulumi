package io.pulumi.core;

import io.pulumi.serialization.Constants;


public abstract class RemoteAsset extends Asset {
   protected RemoteAsset(String uri) {
       super(Constants.assetOrArchiveUriName, uri);
   } 
}
