package com.pivot.banking.account.domain.exception;

/** Signals that an account operation violates a product or ownership rule. */
public class BusinessRuleViolationException extends RuntimeException {

  /** Creates the exception with a user-readable rule violation. */
  public BusinessRuleViolationException(String message) {
    super(message);
  }
}
