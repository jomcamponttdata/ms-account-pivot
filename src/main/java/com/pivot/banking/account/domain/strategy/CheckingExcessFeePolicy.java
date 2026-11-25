package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.Money;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Checking free-movement quota and excess fee, both externalized with zero defaults. */
@Component
public class CheckingExcessFeePolicy implements ExcessMovementFeePolicy {

  private final int freeMovements;
  private final BigDecimal fee;

  /** Creates the policy from the configured free-movement quota and excess-movement fee. */
  public CheckingExcessFeePolicy(
      @Value("${account.free-movements.checking:0}") int freeMovements,
      @Value("${account.excess-fee.checking:0}") BigDecimal fee) {
    this.freeMovements = freeMovements;
    this.fee = fee;
  }

  @Override
  public boolean supports(AccountType accountType) {
    return accountType == AccountType.CHECKING;
  }

  @Override
  public int freeMovements(Account account) {
    return freeMovements;
  }

  @Override
  public Money fee(Account account) {
    return new Money(fee, account.balance().currency());
  }
}
