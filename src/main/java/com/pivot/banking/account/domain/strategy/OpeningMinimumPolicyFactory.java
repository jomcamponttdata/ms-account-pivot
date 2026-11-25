package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.exception.BusinessRuleViolationException;
import java.util.List;
import org.springframework.stereotype.Component;

/** Selects the configured opening-minimum strategy for an account product. */
@Component
public class OpeningMinimumPolicyFactory {

  private final List<OpeningMinimumPolicy> policies;

  /** Creates the factory with all product-specific opening-minimum policies. */
  public OpeningMinimumPolicyFactory(List<OpeningMinimumPolicy> policies) {
    this.policies = List.copyOf(policies);
  }

  /** Returns the policy that supports the given account product. */
  public OpeningMinimumPolicy get(AccountType accountType) {
    return policies.stream()
        .filter(policy -> policy.supports(accountType))
        .findFirst()
        .orElseThrow(
            () ->
                new BusinessRuleViolationException(
                    "Opening minimum policy unavailable for the account type"));
  }
}
