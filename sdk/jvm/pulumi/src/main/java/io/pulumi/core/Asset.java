package io.pulumi.core;

import io.pulumi.serialization.Constants;


public abstract class Asset extends AssetOrArchive{
   protected Asset(String propName, Object value) {
       super(Constants.specialAssetSig, propName, value);
   } 
}
