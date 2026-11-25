package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.Money;
import com.pivot.banking.account.domain.MovementType;
import com.pivot.banking.account.domain.exception.BusinessRuleViolationException;
import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.stereotype.Component;

/** Enforces fixed-term account's allowed movement day and one movement per month. */
@Component
public class FixedTermOperationRule implements AccountOperationRule {

  /** Rejects operations outside the configured day or after the monthly operation. */
  @Override
  public void validate(Account account, MovementType movementType, Money amount, LocalDate date) {
    if (date.getDayOfMonth() != account.allowedMovementDay()) {
      throw new BusinessRuleViolationException("Movement is allowed only on the configured day");
    }
    if (YearMonth.from(date).equals(account.movementsPeriod())
        && account.movementsInCurrentMonth() > 0) {
      throw new BusinessRuleViolationException("Fixed-term account allows one monthly movement");
    }
    SavingsOperationRule.validateCurrencyAndFunds(account, movementType, amount);
  }
}
