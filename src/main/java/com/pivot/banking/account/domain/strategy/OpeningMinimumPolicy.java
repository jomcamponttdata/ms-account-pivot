package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.Money;

/** Strategy for the configured minimum initial balance of an account product. */
public interface OpeningMinimumPolicy {

  /** Whether this policy applies to the given account product. */
  boolean supports(AccountType accountType);

  /** Returns the configured minimum expressed in the account currency. */
  Money minimumFor(AccountType accountType, String currency);
}
