package com.pivot.banking.account.application.port.out;

import com.pivot.banking.account.domain.CustomerSnapshot;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Outbound client port for customer-service. */
public interface CustomerGateway {

    /** Retrieves the customer data required to validate account ownership. */
    Mono<CustomerSnapshot> findById(UUID customerId);
}
