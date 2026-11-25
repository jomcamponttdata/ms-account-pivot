package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.Money;

/** Strategy for the free monthly movement quota and the fee charged beyond it. */
public interface ExcessMovementFeePolicy {

  /** Whether this policy applies to the given account product. */
  boolean supports(AccountType accountType);

  /** Returns how many movements the account may perform without a fee. */
  int freeMovements(Account account);

  /** Returns the fee charged per excess movement, expressed in the account currency. */
  Money fee(Account account);
}
