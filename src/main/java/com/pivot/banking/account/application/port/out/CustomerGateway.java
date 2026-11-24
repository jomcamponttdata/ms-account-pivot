package com.pivot.banking.account.application.port.out;

import com.pivot.banking.account.domain.CustomerSnapshot;
import io.reactivex.rxjava3.core.Maybe;

import java.util.UUID;

/** Outbound client port for customer-service. */
public interface CustomerGateway {

    /** Retrieves the customer data required to validate account ownership. */
    Maybe<CustomerSnapshot> findById(UUID customerId, String correlationId);
}
