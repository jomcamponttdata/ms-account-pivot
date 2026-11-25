package com.pivot.banking.account.domain;

import java.util.UUID;

/** Minimal customer data required by account-opening rules. */
public record CustomerSnapshot(
    UUID id, CustomerType customerType, boolean active, CustomerProfile customerProfile) {

  /** Keeps ordinary customers without a profile compatible with existing callers. */
  public CustomerSnapshot(UUID id, CustomerType customerType, boolean active) {
    this(id, customerType, active, null);
  }
}
