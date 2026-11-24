package com.pivot.banking.account.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/** Provides the shared reactive HTTP client builder for outbound adapters. */
@Configuration
public class WebClientConfiguration {

    /** Creates a reusable builder; adapters configure only their externalized base URLs. */
    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
