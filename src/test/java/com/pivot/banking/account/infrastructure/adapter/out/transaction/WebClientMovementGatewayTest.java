package com.pivot.banking.account.infrastructure.adapter.out.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.CustomerType;
import com.pivot.banking.account.domain.Money;
import com.pivot.banking.account.domain.MovementReceipt;
import com.pivot.banking.account.domain.MovementType;
import com.pivot.banking.account.domain.exception.DownstreamServiceException;
import io.reactivex.rxjava3.core.Single;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ClientCodecConfigurer;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class WebClientMovementGatewayTest {

  private static final String TRANSACTION_URL = "http://transaction.test";

  @Mock private WebClient.Builder builder;

  @Test
  void registersMovementAndReturnsMappedReceipt() {
    // Arrange
    Account account = account();
    Money amount = new Money(new BigDecimal("25.75"), "PEN");
    AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
    AtomicReference<String> capturedBody = new AtomicReference<>();
    WebClientMovementGateway gateway =
        gateway(
            request -> {
              capturedRequest.set(request);
              capturedBody.set(readBody(request));
              return jsonResponse(
                  HttpStatus.CREATED,
                  """
                  {
                    "id":"movement-1",
                    "productId":"%s",
                    "productType":"BANK_ACCOUNT",
                    "movementType":"DEPOSIT",
                    "amount":25.75,
                    "currency":"PEN",
                    "createdAt":"2026-01-05T10:15:30Z"
                  }
                  """
                      .formatted(account.id()));
            });

    // Act
    Single<MovementReceipt> result =
        gateway.register(account, MovementType.DEPOSIT, amount, "Cash deposit", null);

    // Assert
    result
        .test()
        .assertResult(
            new MovementReceipt(
                "movement-1",
                MovementType.DEPOSIT,
                new BigDecimal("25.75"),
                "PEN",
                Instant.parse("2026-01-05T10:15:30Z")))
        .assertComplete();
    assertEquals(HttpMethod.POST, capturedRequest.get().method());
    assertEquals(URI.create(TRANSACTION_URL + "/api/v1/movements"), capturedRequest.get().url());
    String requestBody = capturedBody.get();
    assertTrue(requestBody.contains("\"productId\":\"" + account.id() + "\""));
    assertTrue(requestBody.contains("\"productType\":\"BANK_ACCOUNT\""));
    assertTrue(requestBody.contains("\"movementType\":\"DEPOSIT\""));
    assertTrue(requestBody.contains("\"amount\":25.75"));
    assertTrue(requestBody.contains("\"currency\":\"PEN\""));
    assertTrue(requestBody.contains("\"description\":\"Cash deposit\""));
    verify(builder).baseUrl(TRANSACTION_URL);
    verify(builder).build();
  }

  @Test
  void mapsClientErrorResponseToConfirmedFailure() {
    // Arrange
    Account account = account();
    WebClientMovementGateway gateway =
        gateway(request -> response(HttpStatus.BAD_REQUEST, "invalid movement"));

    // Act
    Single<MovementReceipt> result =
        gateway.register(
            account,
            MovementType.WITHDRAWAL,
            new Money(new BigDecimal("10.00"), "PEN"),
            "ATM",
            null);

    // Assert
    result
        .test()
        .assertError(
            error ->
                error instanceof DownstreamServiceException failure
                    && !failure.isAmbiguous()
                    && failure
                        .getMessage()
                        .equals("Transaction service returned an error: invalid movement"));
  }

  @Test
  void mapsServerErrorResponseToAmbiguousFailure() {
    // Arrange
    Account account = account();
    WebClientMovementGateway gateway =
        gateway(request -> response(HttpStatus.INTERNAL_SERVER_ERROR, "database unavailable"));

    // Act
    Single<MovementReceipt> result =
        gateway.register(
            account, MovementType.DEPOSIT, new Money(new BigDecimal("10.00"), "PEN"), "Cash", null);

    // Assert
    result
        .test()
        .assertError(
            error ->
                error instanceof DownstreamServiceException failure
                    && failure.isAmbiguous()
                    && failure
                        .getMessage()
                        .equals("Transaction service returned an error: database unavailable"));
  }

  @Test
  void usesDefaultMessageWhenErrorResponseIsEmpty() {
    // Arrange
    Account account = account();
    WebClientMovementGateway gateway =
        gateway(
            request -> Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build()));

    // Act
    Single<MovementReceipt> result =
        gateway.register(
            account, MovementType.DEPOSIT, new Money(new BigDecimal("10.00"), "PEN"), null, null);

    // Assert
    result
        .test()
        .assertError(
            error ->
                error instanceof DownstreamServiceException failure
                    && failure.isAmbiguous()
                    && failure
                        .getMessage()
                        .equals("Transaction service returned an error: Movement service failed"));
  }

  @Test
  void mapsTimeoutTransportFailureToAmbiguousOutcome() {
    // Arrange
    Account account = account();
    AtomicReference<Throwable> transportCause = new AtomicReference<>();
    WebClientMovementGateway gateway =
        gateway(
            request -> {
              var timeout = new SocketTimeoutException("Movement service timed out");
              transportCause.set(timeout);
              return Mono.error(
                  new WebClientRequestException(
                      timeout, HttpMethod.POST, request.url(), request.headers()));
            });

    // Act
    Single<MovementReceipt> result =
        gateway.register(
            account, MovementType.DEPOSIT, new Money(new BigDecimal("10.00"), "PEN"), null, null);

    // Assert
    result
        .test()
        .assertError(
            error ->
                error instanceof DownstreamServiceException failure
                    && failure.isAmbiguous()
                    && failure.getMessage().equals("Movement registration outcome is ambiguous")
                    && failure.getCause() instanceof WebClientRequestException requestFailure
                    && requestFailure.getCause() == transportCause.get());
  }

  @Test
  void mapsUnconfirmableResponseDataToAmbiguousOutcome() {
    // Arrange
    Account account = account();
    WebClientMovementGateway gateway =
        gateway(
            request ->
                jsonResponse(
                    HttpStatus.CREATED,
                    """
                    {
                      "id":"movement-1",
                      "productId":"%s",
                      "productType":"BANK_ACCOUNT",
                      "movementType":"UNKNOWN",
                      "amount":25.75,
                      "currency":"PEN",
                      "createdAt":"2026-01-05T10:15:30Z"
                    }
                    """
                        .formatted(account.id())));

    // Act
    Single<MovementReceipt> result =
        gateway.register(
            account, MovementType.DEPOSIT, new Money(new BigDecimal("25.75"), "PEN"), null, null);

    // Assert
    result
        .test()
        .assertError(
            error ->
                error instanceof DownstreamServiceException failure
                    && failure.isAmbiguous()
                    && failure
                        .getMessage()
                        .equals("Movement registration response could not be confirmed")
                    && failure.getCause() instanceof IllegalArgumentException);
  }

  @Test
  void mapsEmptySuccessfulResponseToAmbiguousOutcome() {
    // Arrange
    Account account = account();
    WebClientMovementGateway gateway =
        gateway(request -> Mono.just(ClientResponse.create(HttpStatus.OK).build()));

    // Act
    Single<MovementReceipt> result =
        gateway.register(
            account, MovementType.DEPOSIT, new Money(new BigDecimal("10.00"), "PEN"), null, null);

    // Assert
    result
        .test()
        .assertError(
            error -> error instanceof DownstreamServiceException failure && failure.isAmbiguous());
  }

  @Test
  void boundsAStalledMovementRequestAndReportsAmbiguousOutcome() {
    // Arrange
    WebClientMovementGateway gateway = gateway(request -> Mono.never(), 50);

    // Act
    Single<MovementReceipt> result =
        gateway.register(
            account(), MovementType.DEPOSIT, new Money(new BigDecimal("10.00"), "PEN"), null, null);

    // Assert
    result
        .test()
        .awaitDone(2, java.util.concurrent.TimeUnit.SECONDS)
        .assertError(
            error ->
                error instanceof DownstreamServiceException failure
                    && failure.isAmbiguous()
                    && failure.getCause() instanceof TimeoutException);
  }

  @Test
  void propagatesExplicitCorrelationId() {
    // Arrange
    Account account = account();
    AtomicReference<String> correlationHeader = new AtomicReference<>();
    WebClientMovementGateway gateway =
        gateway(
            request -> {
              correlationHeader.set(request.headers().getFirst("X-Correlation-ID"));
              return jsonResponse(
                  HttpStatus.CREATED,
                  """
                  {
                    "id":"movement-1",
                    "productId":"%s",
                    "productType":"BANK_ACCOUNT",
                    "movementType":"DEPOSIT",
                    "amount":25.75,
                    "currency":"PEN",
                    "createdAt":"2026-01-05T10:15:30Z"
                  }
                  """
                      .formatted(account.id()));
            });

    // Act
    Single<MovementReceipt> result =
        gateway.register(
            account,
            MovementType.DEPOSIT,
            new Money(new BigDecimal("25.75"), "PEN"),
            null,
            "correlation-123");

    // Assert
    result.test().assertValueCount(1).assertComplete();
    assertEquals("correlation-123", correlationHeader.get());
  }

  @Test
  void omitsBlankExplicitCorrelationIdFromOutgoingMovementRequest() {
    // Arrange
    Account account = account();
    AtomicReference<String> correlationHeader = new AtomicReference<>();
    WebClientMovementGateway gateway =
        gateway(
            request -> {
              correlationHeader.set(request.headers().getFirst("X-Correlation-ID"));
              return jsonResponse(
                  HttpStatus.CREATED,
                  """
                  {
                    "id":"movement-1",
                    "productId":"%s",
                    "productType":"BANK_ACCOUNT",
                    "movementType":"DEPOSIT",
                    "amount":25.75,
                    "currency":"PEN",
                    "createdAt":"2026-01-05T10:15:30Z"
                  }
                  """
                      .formatted(account.id()));
            });

    // Act
    Single<MovementReceipt> result =
        gateway.register(
            account, MovementType.DEPOSIT, new Money(new BigDecimal("25.75"), "PEN"), null, " ");

    // Assert
    result.test().assertValueCount(1).assertComplete();
    assertEquals(null, correlationHeader.get());
  }

  @Test
  void rejectsReceiptsWhoseIdentityOrValuesDoNotMatchTheRequest() {
    // Arrange
    Account account = account();
    String validId = "movement-1";
    String productId = account.id().toString();
    java.util.List<String> mismatchedBodies =
        java.util.List.of(
            """
{"id":null,"productId":"%s","productType":"BANK_ACCOUNT","movementType":"DEPOSIT","amount":25.75,"currency":"PEN","createdAt":"2026-01-05T10:15:30Z"}
"""
                .formatted(productId),
            """
{"id":"","productId":"%s","productType":"BANK_ACCOUNT","movementType":"DEPOSIT","amount":25.75,"currency":"PEN","createdAt":"2026-01-05T10:15:30Z"}
"""
                .formatted(productId),
            """
{"id":"%s","productId":"%s","productType":"BANK_ACCOUNT","movementType":"DEPOSIT","amount":25.75,"currency":"PEN","createdAt":"2026-01-05T10:15:30Z"}
"""
                .formatted(validId, UUID.randomUUID()),
            """
{"id":"%s","productId":"%s","productType":"CREDIT","movementType":"DEPOSIT","amount":25.75,"currency":"PEN","createdAt":"2026-01-05T10:15:30Z"}
"""
                .formatted(validId, productId),
            """
{"id":"%s","productId":"%s","productType":"BANK_ACCOUNT","movementType":"DEPOSIT","amount":25.76,"currency":"PEN","createdAt":"2026-01-05T10:15:30Z"}
"""
                .formatted(validId, productId),
            """
{"id":"%s","productId":"%s","productType":"BANK_ACCOUNT","movementType":"DEPOSIT","amount":25.75,"currency":"USD","createdAt":"2026-01-05T10:15:30Z"}
"""
                .formatted(validId, productId),
            """
{"id":"%s","productId":"%s","productType":"BANK_ACCOUNT","movementType":"DEPOSIT","amount":25.75,"currency":"PEN","createdAt":null}
"""
                .formatted(validId, productId));

    // Act
    java.util.List<Single<MovementReceipt>> results =
        mismatchedBodies.stream()
            .map(
                body ->
                    gateway(request -> jsonResponse(HttpStatus.CREATED, body))
                        .register(
                            account,
                            MovementType.DEPOSIT,
                            new Money(new BigDecimal("25.75"), "PEN"),
                            null,
                            null))
            .toList();

    // Assert
    for (Single<MovementReceipt> result : results) {
      result
          .test()
          .assertError(
              error ->
                  error instanceof DownstreamServiceException failure && failure.isAmbiguous());
    }
  }

  private WebClientMovementGateway gateway(ExchangeFunction exchangeFunction) {
    return gateway(exchangeFunction, 3000);
  }

  private WebClientMovementGateway gateway(ExchangeFunction exchangeFunction, long timeoutMs) {
    when(builder.baseUrl(TRANSACTION_URL)).thenReturn(builder);
    when(builder.build())
        .thenReturn(
            WebClient.builder()
                .baseUrl(TRANSACTION_URL)
                .exchangeFunction(exchangeFunction)
                .build());
    ReactiveCircuitBreakerFactory circuitBreakerFactory = mock(ReactiveCircuitBreakerFactory.class);
    when(circuitBreakerFactory.create(anyString())).thenReturn(passThroughCircuitBreaker());
    return new WebClientMovementGateway(builder, TRANSACTION_URL, timeoutMs, circuitBreakerFactory);
  }

  private ReactiveCircuitBreaker passThroughCircuitBreaker() {
    return new ReactiveCircuitBreaker() {
      @Override
      public <T> Mono<T> run(Mono<T> toRun, Function<Throwable, Mono<T>> fallback) {
        return toRun;
      }

      @Override
      public <T> Flux<T> run(Flux<T> toRun, Function<Throwable, Flux<T>> fallback) {
        return toRun;
      }
    };
  }

  private Account account() {
    return new Account(
        UUID.randomUUID(),
        "19100012345678",
        AccountType.SAVINGS,
        new Money(new BigDecimal("100.50"), "PEN"),
        List.of(UUID.randomUUID()),
        CustomerType.PERSONAL,
        List.of(),
        null,
        10,
        null,
        0,
        YearMonth.parse("2026-01"),
        1L,
        Instant.parse("2026-01-02T03:04:05Z"));
  }

  private Mono<ClientResponse> jsonResponse(HttpStatus status, String body) {
    return Mono.just(
        ClientResponse.create(status)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build());
  }

  private Mono<ClientResponse> response(HttpStatus status, String body) {
    return Mono.just(ClientResponse.create(status).body(body).build());
  }

  private String readBody(ClientRequest request) {
    var output = new MockClientHttpRequest(HttpMethod.POST, request.url());
    BodyInserter.Context context =
        new BodyInserter.Context() {
          @Override
          public List<HttpMessageWriter<?>> messageWriters() {
            return ClientCodecConfigurer.create().getWriters();
          }

          @Override
          public Map<String, Object> hints() {
            return Map.of();
          }

          @Override
          public Optional<ServerHttpRequest> serverRequest() {
            return Optional.empty();
          }
        };
    request.body().insert(output, context).block();
    return output.getBodyAsString().block();
  }
}
