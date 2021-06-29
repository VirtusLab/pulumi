package io.pulumi.core;

import io.pulumi.serialization.Constants;


public abstract class Archive extends AssetOrArchive{
   protected Archive(String propName, Object value) {
       super(Constants.specialArchiveSig, propName, value);
   } 
}
