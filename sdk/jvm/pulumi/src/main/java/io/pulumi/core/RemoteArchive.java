package io.pulumi.core;

import io.pulumi.serialization.Constants;


public abstract class RemoteArchive extends Archive{
   protected RemoteArchive(String path) {
       super(Constants.assetOrArchiveUriName, path);
   } 
}
