package com.pivot.banking.account.infrastructure.adapter.out.credit;

import com.pivot.banking.account.application.port.out.CreditCardGateway;
import com.pivot.banking.account.domain.exception.DownstreamServiceException;
import io.reactivex.rxjava3.core.Single;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.adapter.rxjava.RxJava3Adapter;
import reactor.core.publisher.Mono;

/** Reactive HTTP adapter for credit-service card queries with circuit breaker and 2s timeout. */
@Component
public class WebClientCreditCardGateway implements CreditCardGateway {

  private final WebClient client;
  private final ReactiveCircuitBreaker circuitBreaker;

  /** Creates the client using the externally configured credit-service endpoint. */
  public WebClientCreditCardGateway(
      WebClient.Builder builder,
      @Value("${account.services.credit-url}") String creditServiceUrl,
      ReactiveCircuitBreakerFactory circuitBreakerFactory) {
    this.client = builder.baseUrl(creditServiceUrl).build();
    this.circuitBreaker = circuitBreakerFactory.create("creditService");
  }

  /** Reports whether the customer owns a card, using one element of the paginated response. */
  @Override
  public Single<Boolean> hasCard(UUID customerId, String correlationId) {
    return RxJava3Adapter.monoToSingle(hasCardReactor(customerId, correlationId));
  }

  /** Performs the HTTP request with WebClient's native Reactor publishers. */
  private Mono<Boolean> hasCardReactor(UUID customerId, String correlationId) {
    WebClient.RequestHeadersSpec<?> specification =
        client
            .get()
            .uri(
                uriBuilder ->
                    uriBuilder
                        .path("/api/v1/credit-cards")
                        .queryParam("customerId", customerId)
                        .queryParam("size", 1)
                        .build());
    if (correlationId != null && !correlationId.isBlank()) {
      specification = specification.header("X-Correlation-ID", correlationId);
    }
    Mono<Boolean> result =
        specification
            .retrieve()
            .onStatus(
                status -> status.isError(),
                response ->
                    response
                        .bodyToMono(String.class)
                        .defaultIfEmpty("Credit service failed")
                        .map(
                            body ->
                                new DownstreamServiceException(
                                    "Credit service returned an error: " + body, false, null)))
            .bodyToMono(new ParameterizedTypeReference<List<Object>>() {})
            .map(cards -> !cards.isEmpty())
            .defaultIfEmpty(false)
            .onErrorMap(
                WebClientRequestException.class,
                failure ->
                    new DownstreamServiceException("Credit service is unavailable", false, failure))
            .onErrorMap(
                failure -> !(failure instanceof DownstreamServiceException),
                failure ->
                    new DownstreamServiceException(
                        "Credit service returned an invalid response", false, failure))
            .timeout(Duration.ofSeconds(2));
    return circuitBreaker.run(result);
  }
}
