package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.CustomerProfile;
import com.pivot.banking.account.domain.CustomerSnapshot;
import com.pivot.banking.account.domain.CustomerType;
import com.pivot.banking.account.domain.Money;
import com.pivot.banking.account.domain.exception.BusinessRuleViolationException;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;

/** Applies PYME checking rules: active PYME business holders, no maintenance fee. */
@Component
public class PymeCheckingAccountPolicy implements AccountProfilePolicy {

  /** Selects this policy only for PYME checking accounts. */
  @Override
  public boolean supports(CustomerProfile profile, AccountType accountType) {
    return profile == CustomerProfile.PYME && accountType == AccountType.CHECKING;
  }

  /** Rejects any holder that is not an active PYME business customer. */
  @Override
  public void validate(Account account, List<CustomerSnapshot> holders) {
    boolean allPymeBusiness =
        !holders.isEmpty()
            && holders.stream()
                .allMatch(
                    holder ->
                        holder.customerType() == CustomerType.BUSINESS
                            && holder.customerProfile() == CustomerProfile.PYME
                            && holder.active());
    if (!allPymeBusiness) {
      throw new BusinessRuleViolationException(
          "PYME checking accounts require active PYME business holders");
    }
  }

  /** PYME checking accounts require a credit card for every holder. */
  @Override
  public boolean requiresCreditCard() {
    return true;
  }

  /** Forces a zero maintenance fee instead of rejecting a non-zero request. */
  @Override
  public Money effectiveMaintenanceFee(Money requestedFee) {
    return requestedFee == null ? null : new Money(BigDecimal.ZERO, requestedFee.currency());
  }
}
