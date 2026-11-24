package com.pivot.banking.account.infrastructure.adapter.out.customer;

import com.pivot.banking.account.domain.CustomerSnapshot;
import com.pivot.banking.account.domain.CustomerType;
import com.pivot.banking.account.domain.exception.CustomerNotFoundException;
import com.pivot.banking.account.domain.exception.DownstreamServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import io.reactivex.rxjava3.core.Maybe;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.SocketTimeoutException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebClientCustomerGatewayTest {

    private static final String CUSTOMER_URL = "http://customer.test";

    @Mock
    private WebClient.Builder builder;

    @Test
    void retrievesActiveCustomerAndSendsExpectedRequest() {
        // Arrange
        UUID customerId = UUID.randomUUID();
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        WebClientCustomerGateway gateway = gateway(request -> {
            capturedRequest.set(request);
            return jsonResponse(HttpStatus.OK, """
                    {"customerType":"PERSONAL","status":"active"}
                    """);
        });

        // Act
        Maybe<CustomerSnapshot> result = gateway.findById(customerId, null);

        // Assert
        result.test().assertResult(
                new CustomerSnapshot(customerId, CustomerType.PERSONAL, true)).assertComplete();
        assertEquals(HttpMethod.GET, capturedRequest.get().method());
        assertEquals(URI.create(CUSTOMER_URL + "/api/v1/customers/" + customerId), capturedRequest.get().url());
        verify(builder).baseUrl(CUSTOMER_URL);
        verify(builder).build();
    }

    @Test
    void mapsNonActiveBusinessCustomerToInactiveSnapshot() {
        // Arrange
        UUID customerId = UUID.randomUUID();
        WebClientCustomerGateway gateway = gateway(request -> jsonResponse(HttpStatus.OK, """
                {"customerType":"BUSINESS","status":"SUSPENDED"}
                """));

        // Act
        Maybe<CustomerSnapshot> result = gateway.findById(customerId, null);

        // Assert
        result.test().assertResult(
                new CustomerSnapshot(customerId, CustomerType.BUSINESS, false)).assertComplete();
    }

    @Test
    void mapsNotFoundResponseToCustomerNotFound() {
        // Arrange
        UUID customerId = UUID.randomUUID();
        WebClientCustomerGateway gateway = gateway(request -> jsonResponse(
                HttpStatus.NOT_FOUND, "{\"title\":\"Not found\"}"));

        // Act
        Maybe<CustomerSnapshot> result = gateway.findById(customerId, null);

        // Assert
        result.test().assertError(error ->
                error instanceof CustomerNotFoundException
                        && error.getMessage().equals("Customer not found: " + customerId));
    }

    @Test
    void includesServiceErrorResponseBodyInConfirmedFailure() {
        // Arrange
        UUID customerId = UUID.randomUUID();
        WebClientCustomerGateway gateway = gateway(request -> response(
                HttpStatus.INTERNAL_SERVER_ERROR, "database unavailable"));

        // Act
        Maybe<CustomerSnapshot> result = gateway.findById(customerId, null);

        // Assert
        result.test().assertError(error ->
                error instanceof DownstreamServiceException failure
                        && !failure.isAmbiguous()
                        && failure.getMessage().equals(
                                "Customer service returned an error: database unavailable"));
    }

    @Test
    void usesDefaultMessageWhenServiceErrorResponseIsEmpty() {
        // Arrange
        UUID customerId = UUID.randomUUID();
        WebClientCustomerGateway gateway = gateway(request -> Mono.just(
                ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build()));

        // Act
        Maybe<CustomerSnapshot> result = gateway.findById(customerId, null);

        // Assert
        result.test().assertError(error ->
                error instanceof DownstreamServiceException failure && !failure.isAmbiguous()
                        && failure.getMessage().equals(
                                "Customer service returned an error: Customer service failed"));
    }

    @Test
    void rejectsEmptySuccessfulResponse() {
        // Arrange
        UUID customerId = UUID.randomUUID();
        WebClientCustomerGateway gateway = gateway(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK).build()));

        // Act
        Maybe<CustomerSnapshot> result = gateway.findById(customerId, null);

        // Assert
        result.test().assertError(error ->
                error instanceof DownstreamServiceException failure && !failure.isAmbiguous()
                        && failure.getMessage().equals("Customer service returned an empty response"));
    }

    @Test
    void mapsInvalidResponseDataToConfirmedInvalidResponseFailure() {
        // Arrange
        UUID customerId = UUID.randomUUID();
        WebClientCustomerGateway gateway = gateway(request -> jsonResponse(HttpStatus.OK, """
                {"customerType":"UNKNOWN","status":"ACTIVE"}
                """));

        // Act
        Maybe<CustomerSnapshot> result = gateway.findById(customerId, null);

        // Assert
        result.test().assertError(error ->
                error instanceof DownstreamServiceException failure && !failure.isAmbiguous()
                        && failure.getMessage().equals("Customer service returned an invalid response")
                        && failure.getCause() instanceof IllegalArgumentException);
    }

    @Test
    void mapsTimeoutTransportFailureToUnavailableWithoutMarkingItAmbiguous() {
        // Arrange
        UUID customerId = UUID.randomUUID();
        AtomicReference<Throwable> transportCause = new AtomicReference<>();
        WebClientCustomerGateway gateway = gateway(request -> {
            var timeout = new SocketTimeoutException("Customer service timed out");
            transportCause.set(timeout);
            return Mono.error(new WebClientRequestException(timeout,
                    HttpMethod.GET, request.url(), request.headers()));
        });

        // Act
        Maybe<CustomerSnapshot> result = gateway.findById(customerId, null);

        // Assert
        result.test().assertError(error ->
                error instanceof DownstreamServiceException failure && !failure.isAmbiguous()
                        && failure.getMessage().equals("Customer service is unavailable")
                        && failure.getCause() instanceof WebClientRequestException requestFailure
                        && requestFailure.getCause() == transportCause.get());
    }

    @Test
    void propagatesExplicitCorrelationId() {
        // Arrange
        UUID customerId = UUID.randomUUID();
        AtomicReference<String> correlationHeader = new AtomicReference<>();
        WebClientCustomerGateway gateway = gateway(request -> {
            correlationHeader.set(request.headers().getFirst("X-Correlation-ID"));
            return jsonResponse(HttpStatus.OK, """
                    {"customerType":"PERSONAL","status":"ACTIVE"}
                    """);
        });

        // Act
        Maybe<CustomerSnapshot> result = gateway.findById(customerId, "correlation-123");

        // Assert
        result.test().assertValueCount(1).assertComplete();
        assertEquals("correlation-123", correlationHeader.get());
    }

    @Test
    void omitsBlankExplicitCorrelationIdFromOutgoingRequest() {
        // Arrange
        UUID customerId = UUID.randomUUID();
        AtomicReference<String> correlationHeader = new AtomicReference<>();
        WebClientCustomerGateway gateway = gateway(request -> {
            correlationHeader.set(request.headers().getFirst("X-Correlation-ID"));
            return jsonResponse(HttpStatus.OK, """
                    {"customerType":"PERSONAL","status":"ACTIVE"}
                    """);
        });

        // Act
        Maybe<CustomerSnapshot> result = gateway.findById(customerId, " ");

        // Assert
        result.test().assertValueCount(1).assertComplete();
        assertEquals(null, correlationHeader.get());
    }

    private WebClientCustomerGateway gateway(ExchangeFunction exchangeFunction) {
        when(builder.baseUrl(CUSTOMER_URL)).thenReturn(builder);
        when(builder.build()).thenReturn(WebClient.builder()
                .baseUrl(CUSTOMER_URL)
                .exchangeFunction(exchangeFunction)
                .build());
        return new WebClientCustomerGateway(builder, CUSTOMER_URL);
    }

    private Mono<ClientResponse> jsonResponse(HttpStatus status, String body) {
        return Mono.just(ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build());
    }

    private Mono<ClientResponse> response(HttpStatus status, String body) {
        return Mono.just(ClientResponse.create(status).body(body).build());
    }
}
