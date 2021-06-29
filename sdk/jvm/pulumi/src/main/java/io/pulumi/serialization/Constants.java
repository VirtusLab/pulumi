package io.pulumi.serialization;

public class Constants {

    /// <summary>
    /// Unknown values are encoded as a distinguished string value.
    /// </summary>
    public static String unknownValue = "04da6b54-80e4-46f7-96ec-b56ff0331ba9";

    /// <summary>
    /// SpecialSigKey is sometimes used to encode type identity inside of a map. See sdk/go/common/resource/properties.go.
    /// </summary>
    public static String specialSigKey = "4dabf18193072939515e22adb298388d";

    /// <summary>
    /// SpecialAssetSig is a randomly assigned hash used to identify assets in maps. See sdk/go/common/resource/asset.go.
    /// </summary>
    public static String specialAssetSig = "c44067f5952c0a294b673a41bacd8c17";

    /// <summary>
    /// SpecialArchiveSig is a randomly assigned hash used to identify archives in maps. See sdk/go/common/resource/asset.go.
    /// </summary>
    public static String specialArchiveSig = "0def7320c3a5731c473e5ecbe6d01bc7";

    /// <summary>
    /// SpecialSecretSig is a randomly assigned hash used to identify secrets in maps. See sdk/go/common/resource/properties.go.
    /// </summary>
    public static String specialSecretSig = "1b47061264138c4ac30d75fd1eb44270";

    /// <summary>
    /// SpecialResourceSig is a randomly assigned hash used to identify resources in maps. See sdk/go/common/resource/properties.go.
    /// </summary>
    public static String specialResourceSig = "5cf8f73096256a8f31e491e813e4eb8e";


    static public String secretValueName = "value";

    static public String assetTextName = "text";
    static public String archiveAssetsName = "assets";

    static public String assetOrArchivePathName = "path";
    static public String assetOrArchiveUriName = "uri";

    static public String resourceUrnName = "urn";
    static public String resourceIdName = "id";
    static public String resourceVersionName = "packageVersion";

    static public String idPropertyName = "id";
    static public String urnPropertyName = "urn"; 
}
