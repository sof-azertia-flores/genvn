package com.genvn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Image generation settings, bound from the {@code image.*} block of the config file.
 * Fully independent of the text provider: its own key, endpoint, model, sizes, budget and
 * concurrency. Disabled by default so a fresh checkout never spends money on pictures.
 */
@ConfigurationProperties(prefix = "image")
public class ImageProperties {

    private boolean enabled = false;
    /** openai (Images API: generations + edits) | disabled */
    private String provider = "openai";
    private String apiKey = "";
    private String baseUrl = "https://api.openai.com/v1";
    private String model = "gpt-image-1";
    /** Must be a size the chosen model supports. Landscape for stage backgrounds. */
    private String backgroundSize = "1536x1024";
    /** Portrait orientation for character sprites. */
    private String portraitSize = "1024x1536";
    /** Provider-specific quality token; blank sends nothing and the provider default applies. */
    private String quality = "";
    /** png | jpeg -- only formats supported by the bundled Java ImageIO decoder. */
    private String outputFormat = "png";
    /**
     * Total wall-clock budget for one provider call, headers and body together. Reference edits
     * at sprite size routinely run for several minutes on gpt-image models, and a proxy may send
     * headers at once and keep the connection alive while it waits, so this must cover the whole
     * generation, not just the wait for the first byte.
     */
    private int timeoutSeconds = 600;
    /** Longest silence tolerated while a response body is arriving; 0 disables. */
    private int idleTimeoutSeconds = 60;
    /** Image workers. Separate from the text pool: a slow image never occupies a text worker. */
    private int concurrency = 4;
    private int queueCapacity = 64;
    /** Provider calls per asset, counting retries after 429/timeouts. */
    private int maxAttempts = 3;
    /** Assets auto-queued when a story is compiled. The rest stay planned until needed. */
    private int firstBatchBudget = 12;
    /**
     * Provider call attempts allowed per story arc, retries and edits included. 0 (the default)
     * means no cap: concurrency, one-request-per-picture and bounded retries still apply.
     */
    private int arcBudget = 0;
    private final Plan plan = new Plan();

    public static class Plan {
        /** Locations to pre-render at compile time (opening location first). */
        private int locations = 3;
        /** Main characters to pre-render a transparent base sprite and one shared card for. */
        private int characters = 3;
        /** Expression variants per character, comma separated; each depends on the base portrait. */
        private String expressions = "worried,suspicious";

        public int getLocations() { return locations; }
        public void setLocations(int locations) { this.locations = locations; }
        public int getCharacters() { return characters; }
        public void setCharacters(int characters) { this.characters = characters; }
        public String getExpressions() { return expressions; }
        public void setExpressions(String expressions) { this.expressions = expressions; }

        public List<String> expressionList() {
            List<String> out = new ArrayList<>();
            if (expressions == null) return out;
            for (String e : expressions.split(",")) {
                String t = e.trim().toLowerCase();
                if (!t.isEmpty() && !t.equals("neutral") && !out.contains(t)) out.add(t);
            }
            return out;
        }
    }

    public boolean hasCredentials() { return apiKey != null && !apiKey.isBlank(); }

    public int[] parseSize(String size, int[] fallback) {
        try {
            String[] parts = size.toLowerCase().split("x");
            int w = Integer.parseInt(parts[0].trim());
            int h = Integer.parseInt(parts[1].trim());
            if (w > 0 && h > 0) return new int[]{w, h};
        } catch (RuntimeException ignored) {
            // fall through to the fallback
        }
        return fallback;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getBackgroundSize() { return backgroundSize; }
    public void setBackgroundSize(String backgroundSize) { this.backgroundSize = backgroundSize; }
    public String getPortraitSize() { return portraitSize; }
    public void setPortraitSize(String portraitSize) { this.portraitSize = portraitSize; }
    public String getQuality() { return quality; }
    public void setQuality(String quality) { this.quality = quality; }
    public String getOutputFormat() { return outputFormat; }
    public void setOutputFormat(String outputFormat) {
        this.outputFormat = outputFormat == null || outputFormat.isBlank() ? "png" : outputFormat.trim().toLowerCase(Locale.ROOT);
    }
    /** Validate before any paid request, without preventing a text-only application from starting. */
    public String outputFormatProblem() {
        if ("png".equals(outputFormat) || "jpeg".equals(outputFormat)) return null;
        return "image.output-format must be png or jpeg; WebP and other formats are not supported by the bundled Java ImageIO decoder";
    }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getIdleTimeoutSeconds() { return idleTimeoutSeconds; }
    public void setIdleTimeoutSeconds(int idleTimeoutSeconds) { this.idleTimeoutSeconds = idleTimeoutSeconds; }
    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public int getFirstBatchBudget() { return firstBatchBudget; }
    public void setFirstBatchBudget(int firstBatchBudget) { this.firstBatchBudget = firstBatchBudget; }
    public int getArcBudget() { return arcBudget; }
    public void setArcBudget(int arcBudget) { this.arcBudget = arcBudget; }
    /** False when image.arc-budget is 0 or negative: there is then no per-arc cap on attempts. */
    public boolean isArcBudgetLimited() { return arcBudget > 0; }
    public Plan getPlan() { return plan; }
}
