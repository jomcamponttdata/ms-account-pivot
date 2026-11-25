package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.exception.BusinessRuleViolationException;
import java.util.List;
import org.springframework.stereotype.Component;

/** Selects the configured excess-movement fee strategy for an account product. */
@Component
public class ExcessMovementFeePolicyFactory {

  private final List<ExcessMovementFeePolicy> policies;

  /** Creates the factory with all product-specific excess-movement fee policies. */
  public ExcessMovementFeePolicyFactory(List<ExcessMovementFeePolicy> policies) {
    this.policies = List.copyOf(policies);
  }

  /** Returns the policy that supports the given account product. */
  public ExcessMovementFeePolicy get(AccountType accountType) {
    return policies.stream()
        .filter(policy -> policy.supports(accountType))
        .findFirst()
        .orElseThrow(
            () ->
                new BusinessRuleViolationException(
                    "Excess movement fee policy unavailable for the account type"));
  }
}
