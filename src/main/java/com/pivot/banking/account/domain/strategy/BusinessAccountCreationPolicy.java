package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.CustomerSnapshot;
import com.pivot.banking.account.domain.CustomerType;
import com.pivot.banking.account.domain.exception.BusinessRuleViolationException;
import java.util.List;
import org.springframework.stereotype.Component;

/** Applies business-customer account requirements. */
@Component
public class BusinessAccountCreationPolicy implements AccountCreationPolicy {

  /** Allows checking accounts with one or more business holders only. */
  @Override
  public void validate(
      Account account, List<CustomerSnapshot> holders, List<Account> existingAccounts) {
    boolean allBusiness =
        !holders.isEmpty()
            && holders.stream()
                .allMatch(
                    holder -> holder.customerType() == CustomerType.BUSINESS && holder.active());
    if (!allBusiness || account.accountType() != AccountType.CHECKING) {
      throw new BusinessRuleViolationException(
          "Business customers may open checking accounts with one or more business holders");
    }
  }
}
