package com.genvn.asset;

/**
 * The single seam between the pipeline and any image model. Nothing outside this package's
 * providers knows HTTP, multipart bodies or vendor payloads.
 */
public interface ImageAssetProvider {

    boolean isEnabled();

    /** Whether {@link #edit} works, i.e. variants can be made FROM a reference picture. */
    boolean supportsEdit();

    boolean supportsTransparentBackground();

    String describe();

    ImageResult generate(ImageRequest request) throws ImageProviderException;

    /** Produce a variant from a reference image. Only called when {@link #supportsEdit()}. */
    ImageResult edit(ImageEditRequest request) throws ImageProviderException;
}
