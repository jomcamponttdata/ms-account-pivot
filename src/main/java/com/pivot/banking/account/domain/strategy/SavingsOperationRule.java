package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.Money;
import com.pivot.banking.account.domain.MovementType;
import com.pivot.banking.account.domain.exception.BusinessRuleViolationException;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * Enforces the savings account currency and funds checks. Movements beyond the monthly quota are
 * accepted and charged by the application service instead of being rejected.
 */
@Component
public class SavingsOperationRule implements AccountOperationRule {

  /** Validates currency consistency and available funds, regardless of the monthly quota. */
  @Override
  public void validate(Account account, MovementType movementType, Money amount, LocalDate date) {
    validateCurrencyAndFunds(account, movementType, amount);
  }

  /** Enforces currency consistency and prevents withdrawals beyond the available balance. */
  static void validateCurrencyAndFunds(Account account, MovementType movementType, Money amount) {
    if (!account.balance().currency().equals(amount.currency())) {
      throw new BusinessRuleViolationException("Operation currency must match account currency");
    }
    if (movementType == MovementType.WITHDRAWAL
        && !account.balance().isGreaterThanOrEqualTo(amount)) {
      throw new BusinessRuleViolationException("Insufficient account balance");
    }
  }
}
