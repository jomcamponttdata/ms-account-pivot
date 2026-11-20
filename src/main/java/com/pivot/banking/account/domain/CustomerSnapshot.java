package com.pivot.banking.account.domain;

import java.util.UUID;

/** Minimal customer data required by account-opening rules. */
public record CustomerSnapshot(UUID id, CustomerType customerType, boolean active) {
}
