package com.genvn.asset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.config.ImageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rebuilds the image provider when enablement, credentials or {@code image.provider} change.
 * Size, model, timeouts and keys on an OpenAI provider are read from {@link ImageProperties}
 * per request.
 */
public final class ReloadableImageProvider implements ImageAssetProvider {

    private static final Logger log = LoggerFactory.getLogger(ReloadableImageProvider.class);

    private final ImageProperties props;
    private final ObjectMapper mapper;
    private volatile ImageAssetProvider delegate;
    private volatile String signature;

    public ReloadableImageProvider(ImageProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        reload();
    }

    public synchronized void reload() {
        String next = signature();
        if (next.equals(signature) && delegate != null) return;
        ImageAssetProvider created = create(props, mapper);
        delegate = created;
        signature = next;
        log.info("Images: {}", created.describe());
    }

    static ImageAssetProvider create(ImageProperties props, ObjectMapper mapper) {
        if (!props.isEnabled()) {
            return new DisabledImageProvider("image.enabled is false");
        }
        if (!props.hasCredentials()) {
            return new DisabledImageProvider("image.api-key is empty");
        }
        if ("openai".equalsIgnoreCase(props.getProvider())) {
            return new OpenAiImageProvider(props, mapper);
        }
        return new DisabledImageProvider("unknown image.provider '" + props.getProvider() + "'");
    }

    private String signature() {
        return props.isEnabled() + "|" + props.hasCredentials() + "|"
                + (props.getProvider() == null ? "" : props.getProvider().trim().toLowerCase());
    }

    private ImageAssetProvider current() {
        ImageAssetProvider provider = delegate;
        if (provider == null) reload();
        return delegate;
    }

    @Override public boolean isEnabled() { return current().isEnabled(); }
    @Override public boolean supportsEdit() { return current().supportsEdit(); }
    @Override public boolean supportsTransparentBackground() { return current().supportsTransparentBackground(); }
    @Override public String describe() { return current().describe(); }
    @Override public ImageResult generate(ImageRequest request) throws ImageProviderException {
        return current().generate(request);
    }
    @Override public ImageResult edit(ImageEditRequest request) throws ImageProviderException {
        return current().edit(request);
    }
}
