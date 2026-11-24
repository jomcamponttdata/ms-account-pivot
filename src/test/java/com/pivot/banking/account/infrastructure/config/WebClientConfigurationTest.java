package com.pivot.banking.account.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Unit tests for outbound WebClient configuration. */
class WebClientConfigurationTest {

    @Test
    void createsReusableWebClientBuilder() {
        // Arrange
        WebClientConfiguration configuration = new WebClientConfiguration();

        // Act
        WebClient.Builder builder = configuration.webClientBuilder();

        // Assert
        assertNotNull(builder);
    }
}
