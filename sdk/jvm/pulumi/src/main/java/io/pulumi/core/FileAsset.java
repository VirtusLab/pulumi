package io.pulumi.core;

import io.pulumi.serialization.Constants;


public abstract class FileAsset extends Asset {
   protected FileAsset(String path) {
       super(Constants.assetOrArchivePathName, path);
   } 
}
