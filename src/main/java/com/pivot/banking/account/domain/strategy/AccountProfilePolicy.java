package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.CustomerProfile;
import com.pivot.banking.account.domain.CustomerSnapshot;
import com.pivot.banking.account.domain.Money;
import java.util.List;

/** Strategy for profile-specific account rules, selected by customer profile and product. */
public interface AccountProfilePolicy {

  /** Reports whether this policy applies to the given profile and product. */
  boolean supports(CustomerProfile profile, AccountType accountType);

  /** Validates the holders of an account governed by this policy. */
  void validate(Account account, List<CustomerSnapshot> holders);

  /** Reports whether every holder must own a credit card. */
  boolean requiresCreditCard();

  /** Returns the maintenance fee to persist; specialised policies may override it. */
  default Money effectiveMaintenanceFee(Money requestedFee) {
    return requestedFee;
  }
}
