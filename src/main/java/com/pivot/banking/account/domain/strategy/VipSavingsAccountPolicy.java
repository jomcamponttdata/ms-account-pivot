package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.CustomerProfile;
import com.pivot.banking.account.domain.CustomerSnapshot;
import com.pivot.banking.account.domain.CustomerType;
import com.pivot.banking.account.domain.exception.BusinessRuleViolationException;
import java.util.List;
import org.springframework.stereotype.Component;

/** Applies VIP savings rules: active VIP personal holders with a credit card each. */
@Component
public class VipSavingsAccountPolicy implements AccountProfilePolicy {

  /** Selects this policy only for VIP savings accounts. */
  @Override
  public boolean supports(CustomerProfile profile, AccountType accountType) {
    return profile == CustomerProfile.VIP && accountType == AccountType.SAVINGS;
  }

  /** Rejects any holder that is not an active VIP personal customer. */
  @Override
  public void validate(Account account, List<CustomerSnapshot> holders) {
    boolean allVipPersonal =
        !holders.isEmpty()
            && holders.stream()
                .allMatch(
                    holder ->
                        holder.customerType() == CustomerType.PERSONAL
                            && holder.customerProfile() == CustomerProfile.VIP
                            && holder.active());
    if (!allVipPersonal) {
      throw new BusinessRuleViolationException(
          "VIP savings accounts require active VIP personal holders");
    }
  }

  /** VIP savings accounts require a credit card for every holder. */
  @Override
  public boolean requiresCreditCard() {
    return true;
  }
}
