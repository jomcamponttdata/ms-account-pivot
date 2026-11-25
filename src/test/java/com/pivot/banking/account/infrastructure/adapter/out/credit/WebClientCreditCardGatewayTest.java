package com.pivot.banking.account.infrastructure.adapter.out.credit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pivot.banking.account.domain.exception.DownstreamServiceException;
import io.reactivex.rxjava3.core.Single;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.UUID;
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
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class WebClientCreditCardGatewayTest {

  private static final String CREDIT_URL = "http://credit.test";

  @Mock private WebClient.Builder builder;

  private ReactiveCircuitBreakerFactory circuitBreakerFactory;

  @Test
  void reportsCardAndSendsExpectedRequest() {
    // Arrange
    UUID customerId = UUID.randomUUID();
    AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
    WebClientCreditCardGateway gateway =
        gateway(
            request -> {
              capturedRequest.set(request);
              return jsonResponse(
                  HttpStatus.OK,
                  """
                  [{"id":"card-1","customerId":"%s"}]
                  """
                      .formatted(customerId));
            });

    // Act
    Single<Boolean> result = gateway.hasCard(customerId, null);

    // Assert
    result.test().assertResult(true).assertComplete();
    assertEquals(HttpMethod.GET, capturedRequest.get().method());
    assertEquals(
        URI.create(CREDIT_URL + "/api/v1/credit-cards?customerId=" + customerId + "&size=1"),
        capturedRequest.get().url());
    verify(builder).baseUrl(CREDIT_URL);
    verify(builder).build();
    verify(circuitBreakerFactory).create("creditService");
  }

  @Test
  void reportsNoCardForEmptyArrayOrEmptyBody() {
    // Arrange
    UUID customerId = UUID.randomUUID();
    WebClientCreditCardGateway emptyArray = gateway(request -> jsonResponse(HttpStatus.OK, "[]"));
    WebClientCreditCardGateway emptyBody =
        gateway(request -> Mono.just(ClientResponse.create(HttpStatus.OK).build()));

    // Act
    Single<Boolean> fromArray = emptyArray.hasCard(customerId, null);
    Single<Boolean> fromEmptyBody = emptyBody.hasCard(customerId, null);

    // Assert
    fromArray.test().assertResult(false).assertComplete();
    fromEmptyBody.test().assertResult(false).assertComplete();
  }

  @Test
  void mapsClientErrorToConfirmedDownstreamFailure() {
    // Arrange
    UUID customerId = UUID.randomUUID();
    WebClientCreditCardGateway gateway =
        gateway(request -> response(HttpStatus.BAD_REQUEST, "invalid customer identifier"));

    // Act
    Single<Boolean> result = gateway.hasCard(customerId, null);

    // Assert
    result
        .test()
        .assertError(
            error ->
                error instanceof DownstreamServiceException failure
                    && !failure.isAmbiguous()
                    && failure
                        .getMessage()
                        .equals("Credit service returned an error: invalid customer identifier"));
  }

  @Test
  void mapsServerErrorWithDefaultMessageToConfirmedDownstreamFailure() {
    // Arrange
    UUID customerId = UUID.randomUUID();
    WebClientCreditCardGateway gateway =
        gateway(
            request -> Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build()));

    // Act
    Single<Boolean> result = gateway.hasCard(customerId, null);

    // Assert
    result
        .test()
        .assertError(
            error ->
                error instanceof DownstreamServiceException failure
                    && !failure.isAmbiguous()
                    && failure
                        .getMessage()
                        .equals("Credit service returned an error: Credit service failed"));
  }

  @Test
  void mapsTransportFailureToUnavailableWithOriginalCause() {
    // Arrange
    UUID customerId = UUID.randomUUID();
    AtomicReference<Throwable> transportCause = new AtomicReference<>();
    WebClientCreditCardGateway gateway =
        gateway(
            request -> {
              var timeout = new SocketTimeoutException("Credit service timed out");
              transportCause.set(timeout);
              return Mono.error(
                  new WebClientRequestException(
                      timeout, HttpMethod.GET, request.url(), request.headers()));
            });

    // Act
    Single<Boolean> result = gateway.hasCard(customerId, null);

    // Assert
    result
        .test()
        .assertError(
            error ->
                error instanceof DownstreamServiceException failure
                    && !failure.isAmbiguous()
                    && failure.getMessage().equals("Credit service is unavailable")
                    && failure.getCause() instanceof WebClientRequestException requestFailure
                    && requestFailure.getCause() == transportCause.get());
  }

  @Test
  void mapsInvalidResponseDataToConfirmedDownstreamFailure() {
    // Arrange
    UUID customerId = UUID.randomUUID();
    WebClientCreditCardGateway gateway =
        gateway(request -> jsonResponse(HttpStatus.OK, "not-json"));

    // Act
    Single<Boolean> result = gateway.hasCard(customerId, null);

    // Assert
    result
        .test()
        .assertError(
            error ->
                error instanceof DownstreamServiceException failure
                    && !failure.isAmbiguous()
                    && failure.getMessage().equals("Credit service returned an invalid response"));
  }

  @Test
  void propagatesExplicitCorrelationIdAndOmitsBlankOne() {
    // Arrange
    UUID customerId = UUID.randomUUID();
    AtomicReference<String> explicitHeader = new AtomicReference<>();
    AtomicReference<String> blankHeader = new AtomicReference<>();
    WebClientCreditCardGateway explicit =
        gateway(
            request -> {
              explicitHeader.set(request.headers().getFirst("X-Correlation-ID"));
              return jsonResponse(HttpStatus.OK, "[]");
            });
    WebClientCreditCardGateway blank =
        gateway(
            request -> {
              blankHeader.set(request.headers().getFirst("X-Correlation-ID"));
              return jsonResponse(HttpStatus.OK, "[]");
            });

    // Act
    explicit.hasCard(customerId, "correlation-123").blockingGet();
    blank.hasCard(customerId, " ").blockingGet();

    // Assert
    assertEquals("correlation-123", explicitHeader.get());
    assertEquals(null, blankHeader.get());
  }

  private WebClientCreditCardGateway gateway(ExchangeFunction exchangeFunction) {
    when(builder.baseUrl(CREDIT_URL)).thenReturn(builder);
    when(builder.build())
        .thenReturn(
            WebClient.builder().baseUrl(CREDIT_URL).exchangeFunction(exchangeFunction).build());
    circuitBreakerFactory = mock(ReactiveCircuitBreakerFactory.class);
    when(circuitBreakerFactory.create(anyString())).thenReturn(passThroughCircuitBreaker());
    return new WebClientCreditCardGateway(builder, CREDIT_URL, circuitBreakerFactory);
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
}
