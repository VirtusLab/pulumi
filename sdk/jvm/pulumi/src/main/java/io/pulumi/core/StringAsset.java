package io.pulumi.core;

import io.pulumi.serialization.Constants;


public abstract class StringAsset extends Asset {
   protected StringAsset(String text) {
       super(Constants.assetTextName, text);
   } 
}
