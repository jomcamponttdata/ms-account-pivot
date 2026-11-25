package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.Money;
import com.pivot.banking.account.domain.MovementType;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/** Applies currency and sufficient-funds checks without a monthly movement cap. */
@Component
public class CheckingOperationRule implements AccountOperationRule {

  /** Validates currency and ensures withdrawals do not overdraw the account. */
  @Override
  public void validate(Account account, MovementType movementType, Money amount, LocalDate date) {
    SavingsOperationRule.validateCurrencyAndFunds(account, movementType, amount);
  }
}
