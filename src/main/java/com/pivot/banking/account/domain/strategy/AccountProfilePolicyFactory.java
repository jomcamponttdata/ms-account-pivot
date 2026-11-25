package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.CustomerProfile;
import java.util.List;
import org.springframework.stereotype.Component;

/** Selects the profile strategy for a customer profile and account product. */
@Component
public class AccountProfilePolicyFactory {

  private final List<AccountProfilePolicy> policies;

  /** Creates the factory with all available profile policies. */
  public AccountProfilePolicyFactory(List<AccountProfilePolicy> policies) {
    this.policies = List.copyOf(policies);
  }

  /** Returns the matching policy, or the standard fallback when no special benefit applies. */
  public AccountProfilePolicy get(CustomerProfile profile, AccountType accountType) {
    if (profile != null) {
      for (AccountProfilePolicy policy : policies) {
        if (policy.supports(profile, accountType)) {
          return policy;
        }
      }
    }
    return policies.stream()
        .filter(StandardAccountProfilePolicy.class::isInstance)
        .findFirst()
        .orElseGet(StandardAccountProfilePolicy::new);
  }
}
