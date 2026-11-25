package com.pivot.banking.account.domain.exception;

import java.util.UUID;

/** Signals that an account changed concurrently during an update. */
public class ConcurrentModificationException extends RuntimeException {

  /** Creates a concurrent-update exception for an account. */
  public ConcurrentModificationException(UUID accountId) {
    super("Account was modified concurrently: " + accountId);
  }
}
