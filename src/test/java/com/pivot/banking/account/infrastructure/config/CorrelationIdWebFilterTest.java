package com.pivot.banking.account.infrastructure.config;

import org.slf4j.MDC;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
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

    @Test
    void logsRequestOutcomesAndRestoresPreviousMdcValue() {
        // Arrange
        String previousCorrelationId = "outer-correlation";
        MDC.put("correlationId", previousCorrelationId);
        var clientError = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/accounts")
                .header("X-Correlation-ID", "client-error-correlation").build());
        clientError.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
        var serverError = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/accounts")
                .header("X-Correlation-ID", "server-error-correlation").build());
        serverError.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
        var failed = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/accounts")
                .header("X-Correlation-ID", "failed-correlation").build());
        WebFilterChain successChain = ignored -> Mono.empty();

        try {
            // Act
            Mono<Void> clientErrorResult = new CorrelationIdWebFilter().filter(clientError, successChain);
            Mono<Void> serverErrorResult = new CorrelationIdWebFilter().filter(serverError, successChain);
            Mono<Void> failedResult = new CorrelationIdWebFilter().filter(
                    failed, ignored -> Mono.error(new IllegalStateException("test failure")));

            // Assert
            StepVerifier.create(clientErrorResult).verifyComplete();
            assertEquals(previousCorrelationId, MDC.get("correlationId"));
            StepVerifier.create(serverErrorResult).verifyComplete();
            assertEquals(previousCorrelationId, MDC.get("correlationId"));
            StepVerifier.create(failedResult).expectError(IllegalStateException.class).verify();
            assertEquals(previousCorrelationId, MDC.get("correlationId"));
        } finally {
            MDC.remove("correlationId");
        }
    }
}
