package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.Money;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Fixed-term opening minimum, externalized with a zero default. */
@Component
public class FixedTermOpeningMinimumPolicy implements OpeningMinimumPolicy {

  private final BigDecimal minimum;

  /** Creates the policy from the configured fixed-term minimum. */
  public FixedTermOpeningMinimumPolicy(
      @Value("${account.opening-minimum.fixed-term:0}") BigDecimal minimum) {
    this.minimum = minimum;
  }

  @Override
  public boolean supports(AccountType accountType) {
    return accountType == AccountType.FIXED_TERM;
  }

  @Override
  public Money minimumFor(AccountType accountType, String currency) {
    return new Money(minimum, currency);
  }
}
