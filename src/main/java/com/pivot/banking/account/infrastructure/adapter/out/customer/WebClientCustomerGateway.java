package com.pivot.banking.account.infrastructure.adapter.out.customer;

import com.pivot.banking.account.application.port.out.CustomerGateway;
import com.pivot.banking.account.domain.CustomerProfile;
import com.pivot.banking.account.domain.CustomerSnapshot;
import com.pivot.banking.account.domain.CustomerType;
import com.pivot.banking.account.domain.exception.CustomerNotFoundException;
import com.pivot.banking.account.domain.exception.DownstreamServiceException;
import io.reactivex.rxjava3.core.Maybe;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.adapter.rxjava.RxJava3Adapter;
import reactor.core.publisher.Mono;

/** Reactive HTTP adapter for customer-service with circuit breaker and 2s timeout. */
@Component
public class WebClientCustomerGateway implements CustomerGateway {

  private final WebClient client;
  private final ReactiveCircuitBreaker circuitBreaker;

  /** Creates the client using the externally configured customer-service endpoint. */
  public WebClientCustomerGateway(
      WebClient.Builder builder,
      @Value("${account.services.customer-url}") String customerServiceUrl,
      ReactiveCircuitBreakerFactory circuitBreakerFactory) {
    this.client = builder.baseUrl(customerServiceUrl).build();
    this.circuitBreaker = circuitBreakerFactory.create("customerService");
  }

  /** Retrieves the customer type and lifecycle state required by account ownership validation. */
  @Override
  public Maybe<CustomerSnapshot> findById(UUID customerId, String correlationId) {
    return RxJava3Adapter.monoToMaybe(findByIdReactor(customerId, correlationId));
  }

  /** Performs the HTTP request with WebClient's native Reactor publishers. */
  private Mono<CustomerSnapshot> findByIdReactor(UUID customerId, String correlationId) {
    WebClient.RequestHeadersSpec<?> specification =
        client.get().uri("/api/v1/customers/{customerId}", customerId);
    if (correlationId != null && !correlationId.isBlank()) {
      specification = specification.header("X-Correlation-ID", correlationId);
    }
    Mono<CustomerSnapshot> result =
        specification
            .retrieve()
            .onStatus(
                status -> status.value() == 404,
                response -> Mono.error(new CustomerNotFoundException(customerId)))
            .onStatus(
                status -> status.isError(),
                response ->
                    response
                        .bodyToMono(String.class)
                        .defaultIfEmpty("Customer service failed")
                        .map(
                            body ->
                                new DownstreamServiceException(
                                    "Customer service returned an error: " + body, false, null)))
            .bodyToMono(CustomerResponse.class)
            .switchIfEmpty(
                Mono.error(
                    new DownstreamServiceException(
                        "Customer service returned an empty response", false, null)))
            .map(
                response ->
                    new CustomerSnapshot(
                        customerId,
                        CustomerType.valueOf(response.customerType()),
                        "ACTIVE".equalsIgnoreCase(response.status()),
                        parseProfile(response.customerProfile())))
            .onErrorMap(
                WebClientRequestException.class,
                failure ->
                    new DownstreamServiceException(
                        "Customer service is unavailable", false, failure))
            .onErrorMap(
                failure ->
                    !(failure instanceof CustomerNotFoundException)
                        && !(failure instanceof DownstreamServiceException),
                failure ->
                    new DownstreamServiceException(
                        "Customer service returned an invalid response", false, failure))
            .timeout(Duration.ofSeconds(2));
    return circuitBreaker.run(result);
  }

  /** Ignores blank or unknown profile values so older customer-service responses stay readable. */
  private static CustomerProfile parseProfile(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return CustomerProfile.valueOf(value);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  private record CustomerResponse(String customerType, String status, String customerProfile) {}
}
