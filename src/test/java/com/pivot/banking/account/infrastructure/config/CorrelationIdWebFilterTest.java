package com.pivot.banking.account.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.ContextView;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Unit tests for correlation identifier propagation. */
class CorrelationIdWebFilterTest {

    @Test
    void propagatesIncomingCorrelationIdToResponseAndReactorContext() {
        // Arrange
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/accounts")
                .header("X-Correlation-ID", "correlation-123").build());
        AtomicReference<ContextView> capturedContext = new AtomicReference<>();
        WebFilterChain chain = ignored -> Mono.deferContextual(context -> {
            capturedContext.set(context);
            return Mono.empty();
        });

        // Act
        Mono<Void> result = new CorrelationIdWebFilter().filter(exchange, chain);

        // Assert
        StepVerifier.create(result).verifyComplete();
        assertEquals("correlation-123", exchange.getResponse().getHeaders().getFirst("X-Correlation-ID"));
        assertEquals("correlation-123", capturedContext.get().get("correlationId"));
    }

    @Test
    void generatesCorrelationIdWhenHeaderIsMissing() {
        // Arrange
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/accounts").build());
        AtomicReference<ContextView> capturedContext = new AtomicReference<>();
        WebFilterChain chain = ignored -> Mono.deferContextual(context -> {
            capturedContext.set(context);
            return Mono.empty();
        });

        // Act
        Mono<Void> result = new CorrelationIdWebFilter().filter(exchange, chain);

        // Assert
        StepVerifier.create(result).verifyComplete();
        String generated = exchange.getResponse().getHeaders().getFirst("X-Correlation-ID");
        assertNotNull(generated);
        assertEquals(generated, capturedContext.get().get("correlationId"));
    }

    @Test
    void generatesCorrelationIdWhenHeaderIsBlank() {
        // Arrange
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/accounts")
                .header("X-Correlation-ID", "  ").build());
        AtomicReference<ContextView> capturedContext = new AtomicReference<>();
        WebFilterChain chain = ignored -> Mono.deferContextual(context -> {
            capturedContext.set(context);
            return Mono.empty();
        });

        // Act
        Mono<Void> result = new CorrelationIdWebFilter().filter(exchange, chain);

        // Assert
        StepVerifier.create(result).verifyComplete();
        String generated = exchange.getResponse().getHeaders().getFirst("X-Correlation-ID");
        assertNotNull(generated);
        assertEquals(false, generated.isBlank());
        assertEquals(generated, capturedContext.get().get("correlationId"));
    }
}
