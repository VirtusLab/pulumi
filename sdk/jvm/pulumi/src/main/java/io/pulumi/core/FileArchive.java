package io.pulumi.core;

import io.pulumi.serialization.Constants;


public abstract class FileArchive extends Archive{
   protected FileArchive(String path) {
       super(Constants.assetOrArchivePathName, path);
   } 
}
