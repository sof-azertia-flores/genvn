package com.genvn.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from the {@code llm.*} block of the config file (backend/config/application.yml).
 *
 * Defaults live here, in code, so the app still runs if that file is deleted -- with no api-key,
 * which means mock mode.
 */
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {
    private String baseUrl = "https://api.openai.com/v1";
    private String apiKey = "";
    private String model = "gpt-4o-mini";
    private double temperature = 0.85;
    /** Total wall-clock budget for one generation, including streaming. */
    private int timeoutSeconds = 300;
    /**
     * Longest silence tolerated once the response has started. The request timeout above only
     * covers the wait for headers; without this, a connection a gateway holds open without
     * sending bytes would block a game thread forever. 0 disables the rule.
     */
    private int idleTimeoutSeconds = 120;
    /** Ask for response_format=json_object. Turn off for providers that reject it. */
    private boolean jsonMode = true;
    /**
     * Stream the response (SSE). Keeps bytes flowing so a gateway in front of the provider does
     * not 504 a long generation. Turn off only for a provider that cannot stream.
     */
    private boolean stream = true;
    /** Force mock even when a key is present -- handy for offline demos and tests. */
    private boolean forceMock = false;
    private final Reasoning reasoning = new Reasoning();

    /**
     * {@code reasoning_effort} for models that support it (gpt-5 family, o-series). Empty sends
     * nothing, so providers that reject the field are unaffected by default. A per-purpose value
     * wins over {@code effort}: compiling a story deserves more thought than ranking cache order.
     */
    public static class Reasoning {
        private String effort = "";
        private String storyCompile = "";
        private String sceneGenerate = "";
        private String arcContinue = "";
        private String choiceProbabilities = "";
        private String spareDesigns = "";

        /** The effort to send for this purpose, or an empty string to send nothing. */
        public String effortFor(LlmPurpose purpose) {
            String specific = purpose == null ? "" : switch (purpose) {
                case STORY_COMPILE -> storyCompile;
                case SCENE_GENERATE -> sceneGenerate;
                case ARC_CONTINUE -> arcContinue;
                case CHOICE_PROBABILITIES -> choiceProbabilities;
                case SPARE_DESIGNS -> spareDesigns;
            };
            String chosen = specific == null || specific.isBlank() ? effort : specific;
            return chosen == null ? "" : chosen.trim().toLowerCase(java.util.Locale.ROOT);
        }

        public String getEffort() { return effort; }
        public void setEffort(String effort) { this.effort = effort; }
        public String getStoryCompile() { return storyCompile; }
        public void setStoryCompile(String storyCompile) { this.storyCompile = storyCompile; }
        public String getSceneGenerate() { return sceneGenerate; }
        public void setSceneGenerate(String sceneGenerate) { this.sceneGenerate = sceneGenerate; }
        public String getArcContinue() { return arcContinue; }
        public void setArcContinue(String arcContinue) { this.arcContinue = arcContinue; }
        public String getChoiceProbabilities() { return choiceProbabilities; }
        public void setChoiceProbabilities(String choiceProbabilities) { this.choiceProbabilities = choiceProbabilities; }
        public String getSpareDesigns() { return spareDesigns; }
        public void setSpareDesigns(String spareDesigns) { this.spareDesigns = spareDesigns; }
    }

    public Reasoning getReasoning() { return reasoning; }

    public boolean hasCredentials() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getIdleTimeoutSeconds() { return idleTimeoutSeconds; }
    public void setIdleTimeoutSeconds(int idleTimeoutSeconds) { this.idleTimeoutSeconds = idleTimeoutSeconds; }
    public boolean isStream() { return stream; }
    public void setStream(boolean stream) { this.stream = stream; }
    public boolean isJsonMode() { return jsonMode; }
    public void setJsonMode(boolean jsonMode) { this.jsonMode = jsonMode; }
    public boolean isForceMock() { return forceMock; }
    public void setForceMock(boolean forceMock) { this.forceMock = forceMock; }
}
