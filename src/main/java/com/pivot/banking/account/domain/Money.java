package com.pivot.banking.account.domain;

import java.math.BigDecimal;

/** Monetary value with an ISO currency code. */
public record Money(BigDecimal amount, String currency) {

  /** Validates the amount precision and currency format. */
  public Money {
    if (amount == null
        || amount.stripTrailingZeros().scale() > 2
        || currency == null
        || !currency.matches("[A-Z]{3}")) {
      throw new IllegalArgumentException("Invalid monetary amount or currency");
    }
  }

  /** Adds a value expressed in the same currency. */
  public Money add(Money other) {
    requireSameCurrency(other);
    return new Money(amount.add(other.amount), currency);
  }

  /** Subtracts a value expressed in the same currency. */
  public Money subtract(Money other) {
    requireSameCurrency(other);
    return new Money(amount.subtract(other.amount), currency);
  }

  /** Returns whether this amount covers the other amount. */
  public boolean isGreaterThanOrEqualTo(Money other) {
    requireSameCurrency(other);
    return amount.compareTo(other.amount) >= 0;
  }

  /** Ensures monetary arithmetic does not combine distinct currencies. */
  private void requireSameCurrency(Money other) {
    if (!currency.equals(other.currency)) {
      throw new IllegalArgumentException("Currencies must match");
    }
  }
}
