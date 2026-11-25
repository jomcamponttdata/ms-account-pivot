package com.pivot.banking.account.domain.exception;

import java.util.UUID;

/** Signals that the requested account does not exist. */
public class AccountNotFoundException extends RuntimeException {

  /** Creates the exception for a missing account identifier. */
  public AccountNotFoundException(UUID id) {
    super("Account not found: " + id);
  }
}
