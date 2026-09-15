package com.genvn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Engine settings, bound from the {@code genvn.*} block of the config file
 * ({@code backend/config/application.yml}).
 *
 * Defaults live here, in code, so the app still runs correctly if the config file is deleted.
 */
@ConfigurationProperties(prefix = "genvn")
public class GenvnProperties {

    /** Where saves are written, relative to the backend working directory. */
    private String dataDir = "data";
    /**
     * How many unassigned appearance-only designs to keep on hand. Whenever a scene gives one
     * an identity, a background call sketches a replacement so the pool stays this size.
     * 0 turns replenishment off; the compiler's initial designs are then all there is.
     */
    private int spareDesigns = 3;
    /**
     * Key the browser must present on every /api call (header X-Genvn-Key). Empty, the local
     * default, means no gate at all: set it before exposing the server beyond loopback.
     */
    private String accessKey = "";
    /**
     * Front-end origins allowed to call /api from another domain, e.g. a CDN-hosted build.
     * Loopback origins are always allowed. Patterns such as https://*.example.com work.
     */
    private List<String> allowedOrigins = new ArrayList<>();

    private final Speculation speculation = new Speculation();
    private final Continuation continuation = new Continuation();

    public static class Speculation {
        /** Generate the successors of every choice while the player is still reading. */
        private boolean enabled = true;
        /** Prefetch budget per scene: one branch per choice, since dice are cast before the prefetch. */
        private int maxBranches = 6;
        /** Size of the background generation pool. */
        private int threads = 4;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxBranches() { return maxBranches; }
        public void setMaxBranches(int maxBranches) { this.maxBranches = maxBranches; }
        public int getThreads() { return threads; }
        public void setThreads(int threads) { this.threads = threads; }
    }

    public static class Continuation {
        /** Plan a follow-up arc in the background when the current spine runs low. */
        private boolean enabled = true;
        /** Fraction of beats completed at which planning starts. */
        private double threshold = 0.6;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getThreshold() { return threshold; }
        public void setThreshold(double threshold) { this.threshold = threshold; }
    }

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public int getSpareDesigns() { return spareDesigns; }
    public void setSpareDesigns(int spareDesigns) { this.spareDesigns = spareDesigns; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey == null ? "" : accessKey; }
    /**
     * UI and model-output language: {@code zh} (default) or {@code en}. Changing it relabels the
     * front end and tells every prompt to write player-visible prose in that language.
     */
    private String language = UiLanguage.ZH;

    public List<String> getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null ? new ArrayList<>() : new ArrayList<>(allowedOrigins);
    }
    public String getLanguage() { return UiLanguage.normalize(language); }
    public void setLanguage(String language) { this.language = UiLanguage.normalize(language); }
    public Speculation getSpeculation() { return speculation; }
    public Continuation getContinuation() { return continuation; }
}
