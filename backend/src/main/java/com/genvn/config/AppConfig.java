package com.genvn.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.asset.ImageAssetProvider;
import com.genvn.asset.ReloadableImageProvider;
import com.genvn.llm.LlmClient;
import com.genvn.llm.LlmProperties;
import com.genvn.llm.ReloadableLlmClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({LlmProperties.class, GenvnProperties.class, ImageProperties.class})
public class AppConfig {

    /**
     * No key, no problem: the whole engine runs against the mock so the core loop is always
     * demonstrable offline. The wrapper rebuilds when credentials or force-mock change.
     */
    @Bean
    public LlmClient llmClient(LlmProperties props, ObjectMapper mapper) {
        return new ReloadableLlmClient(props, mapper);
    }

    /**
     * Pictures are off unless image.enabled is true AND image.api-key is set. Every other case
     * is an explicit, named refusal so the inspector can say exactly why there are no images.
     */
    @Bean
    public ImageAssetProvider imageAssetProvider(ImageProperties props, ObjectMapper mapper) {
        return new ReloadableImageProvider(props, mapper);
    }
}
