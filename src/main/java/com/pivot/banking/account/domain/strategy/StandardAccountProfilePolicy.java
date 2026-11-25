package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.CustomerProfile;
import com.pivot.banking.account.domain.CustomerSnapshot;
import java.util.List;
import org.springframework.stereotype.Component;

/** Fallback policy for ordinary customers and for profile/product combinations without benefits. */
@Component
public class StandardAccountProfilePolicy implements AccountProfilePolicy {

  /** Never selected by matching; the factory uses this policy as its fallback. */
  @Override
  public boolean supports(CustomerProfile profile, AccountType accountType) {
    return false;
  }

  /** Ordinary accounts have no profile-specific holder rules. */
  @Override
  public void validate(Account account, List<CustomerSnapshot> holders) {
    // No profile-specific validation for ordinary accounts.
  }

  /** Ordinary accounts do not require a credit card. */
  @Override
  public boolean requiresCreditCard() {
    return false;
  }
}
