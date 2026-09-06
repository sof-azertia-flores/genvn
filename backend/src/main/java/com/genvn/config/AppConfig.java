package com.genvn.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.asset.DisabledImageProvider;
import com.genvn.asset.ImageAssetProvider;
import com.genvn.asset.OpenAiImageProvider;
import com.genvn.llm.LlmClient;
import com.genvn.llm.LlmProperties;
import com.genvn.llm.MockLlmClient;
import com.genvn.llm.OpenAiCompatibleLlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({LlmProperties.class, GenvnProperties.class, ImageProperties.class})
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    /**
     * No key, no problem: the whole engine runs against the mock so the core loop is always
     * demonstrable offline.
     */
    @Bean
    public LlmClient llmClient(LlmProperties props, ObjectMapper mapper) {
        if (props.isForceMock() || !props.hasCredentials()) {
            String reason = props.isForceMock() ? "forced by llm.force-mock" : "llm.api-key is empty";
            log.info("LLM: using MockLlmClient ({}). Set llm.api-key in backend/config/application.yml for a real model.",
                    reason);
            return new MockLlmClient(mapper, reason);
        }
        log.info("LLM: using OpenAI-compatible endpoint {} with model {}", props.getBaseUrl(), props.getModel());
        return new OpenAiCompatibleLlmClient(props, mapper);
    }

    /**
     * Pictures are off unless image.enabled is true AND image.api-key is set. Every other case
     * is an explicit, named refusal so the inspector can say exactly why there are no images.
     */
    @Bean
    public ImageAssetProvider imageAssetProvider(ImageProperties props, ObjectMapper mapper) {
        if (!props.isEnabled()) {
            log.info("Images: disabled (image.enabled is false). Placeholders will be used.");
            return new DisabledImageProvider("image.enabled is false");
        }
        if (!props.hasCredentials()) {
            log.info("Images: disabled (image.api-key is empty). Set it in backend/config/application.yml.");
            return new DisabledImageProvider("image.api-key is empty");
        }
        if ("openai".equalsIgnoreCase(props.getProvider())) {
            log.info("Images: OpenAI Images API at {} with model {} (concurrency {}, arc budget {})",
                    props.getBaseUrl(), props.getModel(), props.getConcurrency(), props.getArcBudget());
            return new OpenAiImageProvider(props, mapper);
        }
        log.info("Images: disabled (unknown image.provider '{}')", props.getProvider());
        return new DisabledImageProvider("unknown image.provider '" + props.getProvider() + "'");
    }

}
