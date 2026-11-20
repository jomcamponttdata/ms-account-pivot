package com.pivot.banking.account.infrastructure.adapter.out.customer;

import com.pivot.banking.account.application.port.out.CustomerGateway;
import com.pivot.banking.account.domain.CustomerSnapshot;
import com.pivot.banking.account.domain.CustomerType;
import com.pivot.banking.account.domain.exception.CustomerNotFoundException;
import com.pivot.banking.account.domain.exception.DownstreamServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Reactive HTTP adapter for customer-service. */
@Component
public class WebClientCustomerGateway implements CustomerGateway {

    private final WebClient client;

    /** Creates the client using the externally configured customer-service endpoint. */
    public WebClientCustomerGateway(WebClient.Builder builder,
                                    @Value("${account.services.customer-url}") String customerServiceUrl) {
        this.client = builder.baseUrl(customerServiceUrl).build();
    }

    /** Retrieves the customer type and lifecycle state required by account ownership validation. */
    @Override
    public Mono<CustomerSnapshot> findById(UUID customerId) {
        return client.get().uri("/api/v1/customers/{customerId}", customerId)
                .retrieve()
                .onStatus(status -> status.value() == 404,
                        response -> Mono.error(new CustomerNotFoundException(customerId)))
                .onStatus(status -> status.isError(),
                        response -> response.bodyToMono(String.class).defaultIfEmpty("Customer service failed")
                                .map(body -> new DownstreamServiceException(
                                        "Customer service returned an error: " + body, false, null)))
                .bodyToMono(CustomerResponse.class)
                .switchIfEmpty(Mono.error(new DownstreamServiceException(
                        "Customer service returned an empty response", false, null)))
                .map(response -> new CustomerSnapshot(customerId,
                        CustomerType.valueOf(response.customerType()),
                        "ACTIVE".equalsIgnoreCase(response.status())))
                .onErrorMap(WebClientRequestException.class, failure ->
                        new DownstreamServiceException("Customer service is unavailable", false, failure))
                .onErrorMap(failure -> !(failure instanceof CustomerNotFoundException)
                                && !(failure instanceof DownstreamServiceException),
                        failure -> new DownstreamServiceException(
                                "Customer service returned an invalid response", false, failure));
    }

    private record CustomerResponse(String customerType, String status) {
    }
}
