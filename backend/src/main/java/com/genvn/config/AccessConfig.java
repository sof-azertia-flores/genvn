package com.genvn.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.api.AccessGate;
import com.genvn.api.AccessKeyFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.ArrayList;
import java.util.List;

/**
 * Who may call {@code /api} and from where.
 *
 * Two servlet filters, in this order: CORS first, so that every answer -- a refusal included --
 * carries the headers a browser on another origin needs to read it; then the access key.
 * Loopback origins are always allowed, so the local Vite dev server keeps working; a front end
 * served from a CDN is added through {@code genvn.allowed-origins}.
 */
@Configuration
public class AccessConfig {

    private static final Logger log = LoggerFactory.getLogger(AccessConfig.class);

    static final List<String> LOOPBACK_ORIGINS = List.of("http://localhost:*", "http://127.0.0.1:*");

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter(GenvnProperties properties) {
        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(
                new CorsFilter(corsSource(properties.getAllowedOrigins())));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        bean.addUrlPatterns("/api/*");
        return bean;
    }

    @Bean
    public FilterRegistrationBean<AccessKeyFilter> accessKeyFilter(AccessGate gate, ObjectMapper mapper) {
        if (gate.required()) {
            log.info("Access: /api requires the key from genvn.access-key (header {})", AccessGate.HEADER);
        } else {
            log.info("Access: open -- set genvn.access-key before exposing this server beyond loopback");
        }
        FilterRegistrationBean<AccessKeyFilter> bean = new FilterRegistrationBean<>(new AccessKeyFilter(gate, mapper));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        bean.addUrlPatterns("/api/*");
        return bean;
    }

    /** Loopback plus the configured front-end origins; blank entries and trailing slashes are ignored. */
    public static UrlBasedCorsConfigurationSource corsSource(List<String> allowedOrigins) {
        List<String> patterns = new ArrayList<>(LOOPBACK_ORIGINS);
        if (allowedOrigins != null) {
            for (String origin : allowedOrigins) {
                String trimmed = origin == null ? "" : origin.trim().replaceAll("/+$", "");
                if (!trimmed.isEmpty() && !patterns.contains(trimmed)) patterns.add(trimmed);
            }
        }
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(patterns);
        config.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // The key header makes every call preflighted; let the browser remember the answer.
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
