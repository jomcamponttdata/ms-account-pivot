package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.Money;
import com.pivot.banking.account.domain.MovementType;
import java.time.LocalDate;

/** Validates a monetary operation for one account type. */
public interface AccountOperationRule {

  /** Validates monthly product limits, operation date, currency, and funds. */
  void validate(Account account, MovementType movementType, Money amount, LocalDate date);
}
