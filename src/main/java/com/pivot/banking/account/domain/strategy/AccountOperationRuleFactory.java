package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.exception.BusinessRuleViolationException;
import java.util.List;
import org.springframework.stereotype.Component;

/** Selects the operation rule corresponding to an account type. */
@Component
public class AccountOperationRuleFactory {

  private final List<AccountOperationRule> rules;

  /** Creates the factory with all account operation rules. */
  public AccountOperationRuleFactory(List<AccountOperationRule> rules) {
    this.rules = List.copyOf(rules);
  }

  /** Returns the operation rule matching the account type. */
  public AccountOperationRule get(AccountType accountType) {
    return switch (accountType) {
      case SAVINGS ->
          rules.stream()
              .filter(SavingsOperationRule.class::isInstance)
              .findFirst()
              .orElseThrow(() -> new BusinessRuleViolationException("Savings rule unavailable"));
      case CHECKING ->
          rules.stream()
              .filter(CheckingOperationRule.class::isInstance)
              .findFirst()
              .orElseThrow(() -> new BusinessRuleViolationException("Checking rule unavailable"));
      case FIXED_TERM ->
          rules.stream()
              .filter(FixedTermOperationRule.class::isInstance)
              .findFirst()
              .orElseThrow(() -> new BusinessRuleViolationException("Fixed-term rule unavailable"));
    };
  }
}
