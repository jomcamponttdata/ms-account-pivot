package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.Money;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Fixed-term accounts charge no excess fee: their single monthly movement on the allowed day is
 * inherently the whole quota, and further movements are rejected by the operation rule.
 */
@Component
public class FixedTermExcessFeePolicy implements ExcessMovementFeePolicy {

  private static final int FREE_MOVEMENTS = 1;

  @Override
  public boolean supports(AccountType accountType) {
    return accountType == AccountType.FIXED_TERM;
  }

  @Override
  public int freeMovements(Account account) {
    return FREE_MOVEMENTS;
  }

  @Override
  public Money fee(Account account) {
    return new Money(BigDecimal.ZERO, account.balance().currency());
  }
}
