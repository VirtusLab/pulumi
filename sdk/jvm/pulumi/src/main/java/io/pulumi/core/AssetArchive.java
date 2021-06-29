package io.pulumi.core;

import io.pulumi.serialization.Constants;
import java.util.Map;
import com.google.common.collect.ImmutableMap;


public abstract class AssetArchive extends Archive{
   protected AssetArchive(Map<String, AssetOrArchive> assets) {
       super(Constants.archiveAssetsName, ImmutableMap.copyOf(assets));
   } 
}
