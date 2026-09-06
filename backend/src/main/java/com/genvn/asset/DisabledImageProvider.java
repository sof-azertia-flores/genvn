package com.genvn.asset;

/** The provider when pictures are off. Never called with money; every request is a clean refusal. */
public class DisabledImageProvider implements ImageAssetProvider {

    private final String reason;

    public DisabledImageProvider(String reason) {
        this.reason = reason;
    }

    @Override public boolean isEnabled() { return false; }
    @Override public boolean supportsEdit() { return false; }
    @Override public boolean supportsTransparentBackground() { return false; }
    @Override public String describe() { return "disabled (" + reason + ")"; }

    @Override
    public ImageResult generate(ImageRequest request) throws ImageProviderException {
        throw new ImageProviderException("image generation is disabled: " + reason, false);
    }

    @Override
    public ImageResult edit(ImageEditRequest request) throws ImageProviderException {
        throw new ImageProviderException("image generation is disabled: " + reason, false);
    }
}
