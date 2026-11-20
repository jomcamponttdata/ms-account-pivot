package com.pivot.banking.account.domain.exception;

import java.util.UUID;

/** Signals that a referenced customer does not exist. */
public class CustomerNotFoundException extends RuntimeException {

    /** Creates the exception for a missing customer identifier. */
    public CustomerNotFoundException(UUID id) {
        super("Customer not found: " + id);
    }
}
