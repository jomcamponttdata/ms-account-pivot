package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.Money;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Savings excess fee, externalized with a zero default. */
@Component
public class SavingsExcessFeePolicy implements ExcessMovementFeePolicy {

  private final BigDecimal fee;

  /** Creates the policy from the configured excess-movement fee. */
  public SavingsExcessFeePolicy(@Value("${account.excess-fee.savings:0}") BigDecimal fee) {
    this.fee = fee;
  }

  @Override
  public boolean supports(AccountType accountType) {
    return accountType == AccountType.SAVINGS;
  }

  @Override
  public int freeMovements(Account account) {
    return account.maxMonthlyMovements() == null ? 0 : account.maxMonthlyMovements();
  }

  @Override
  public Money fee(Account account) {
    return new Money(fee, account.balance().currency());
  }
}
