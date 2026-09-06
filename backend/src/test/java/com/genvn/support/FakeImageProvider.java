package com.genvn.support;

import com.genvn.asset.ImageAssetProvider;
import com.genvn.asset.ImageEditRequest;
import com.genvn.asset.ImageProviderException;
import com.genvn.asset.ImageRequest;
import com.genvn.asset.ImageResult;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * An image model you can hold at the door. Every call counts itself on entry (so a test can
 * prove a request reached the provider), can be gated (held until released), can be told to
 * fail in a chosen way, and otherwise returns a small but genuine PNG that decodes.
 */
public class FakeImageProvider implements ImageAssetProvider {

    public volatile boolean enabled = true;
    public volatile boolean supportsEdit = true;
    public volatile boolean transparent = true;

    public final AtomicInteger calls = new AtomicInteger();
    public final AtomicInteger generateCalls = new AtomicInteger();
    public final AtomicInteger editCalls = new AtomicInteger();
    public final AtomicInteger active = new AtomicInteger();
    public final AtomicInteger highWater = new AtomicInteger();
    private final List<String> prompts = new ArrayList<>();
    public final List<ImageRequest> generations = java.util.Collections.synchronizedList(new ArrayList<>());
    public final List<ImageEditRequest> edits = java.util.Collections.synchronizedList(new ArrayList<>());
    public volatile boolean forceOpaque = false;

    /** When set, every call blocks here until the test counts it down. */
    public volatile CountDownLatch gate;
    /** Counted down on entry, before any gate, so a test can wait for "the request arrived". */
    public volatile CountDownLatch entered;
    /** Return an exception to throw for a given prompt, or null to succeed. */
    public volatile Function<String, ImageProviderException> failWith;

    @Override public boolean isEnabled() { return enabled; }
    @Override public boolean supportsEdit() { return supportsEdit; }
    @Override public boolean supportsTransparentBackground() { return transparent; }
    @Override public String describe() { return "fake-image-provider"; }

    @Override
    public ImageResult generate(ImageRequest request) throws ImageProviderException {
        generateCalls.incrementAndGet();
        generations.add(request);
        return serve(request.prompt(), request.width(), request.height(), request.transparentBackground());
    }

    @Override
    public ImageResult edit(ImageEditRequest request) throws ImageProviderException {
        editCalls.incrementAndGet();
        edits.add(request);
        return serve(request.prompt(), request.width(), request.height(), request.transparentBackground());
    }

    private ImageResult serve(String prompt, int w, int h, boolean transparent) throws ImageProviderException {
        calls.incrementAndGet();
        int now = active.incrementAndGet();
        highWater.accumulateAndGet(now, Math::max);
        synchronized (prompts) {
            prompts.add(prompt);
        }
        try {
            CountDownLatch e = entered;
            if (e != null) e.countDown();
            CountDownLatch g = gate;
            if (g != null && !g.await(30, TimeUnit.SECONDS)) {
                throw new ImageProviderException("fake provider gate was never released", false);
            }
            Function<String, ImageProviderException> f = failWith;
            if (f != null) {
                ImageProviderException ex = f.apply(prompt);
                if (ex != null) throw ex;
            }
            return new ImageResult(png(prompt, 16, 24, transparent && !forceOpaque), "image/png", w, h, "fake-model");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImageProviderException("interrupted", false);
        } finally {
            active.decrementAndGet();
        }
    }

    public List<String> prompts() {
        synchronized (prompts) {
            return new ArrayList<>(prompts);
        }
    }

    /** A real, decodable PNG whose colour is derived from the prompt, so files are distinguishable. */
    public static byte[] png(String seed, int w, int h) {
        return png(seed, w, h, false);
    }

    public static byte[] png(String seed, int w, int h, boolean transparent) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int hash = seed == null ? 0 : seed.hashCode();
        Color c = new Color(Math.abs(hash) % 256, Math.abs(hash >> 8) % 256, Math.abs(hash >> 16) % 256);
        var g = img.createGraphics();
        g.setColor(c);
        int inset = transparent ? Math.max(1, w / 4) : 0;
        g.fillRect(inset, 0, w - inset * 2, h);
        g.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
