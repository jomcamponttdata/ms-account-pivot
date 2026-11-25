package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.CustomerType;
import com.pivot.banking.account.domain.exception.BusinessRuleViolationException;
import java.util.List;
import org.springframework.stereotype.Component;

/** Selects the account-opening strategy for the customer type. */
@Component
public class AccountCreationPolicyFactory {

  private final List<AccountCreationPolicy> policies;

  /** Creates the factory with all supported policies. */
  public AccountCreationPolicyFactory(List<AccountCreationPolicy> policies) {
    this.policies = List.copyOf(policies);
  }

  /** Returns the matching account-opening strategy. */
  public AccountCreationPolicy get(CustomerType customerType) {
    return switch (customerType) {
      case PERSONAL ->
          policies.stream()
              .filter(PersonalAccountCreationPolicy.class::isInstance)
              .findFirst()
              .orElseThrow(() -> new BusinessRuleViolationException("Personal policy unavailable"));
      case BUSINESS ->
          policies.stream()
              .filter(BusinessAccountCreationPolicy.class::isInstance)
              .findFirst()
              .orElseThrow(() -> new BusinessRuleViolationException("Business policy unavailable"));
    };
  }
}
