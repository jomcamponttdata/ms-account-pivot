package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.CustomerSnapshot;
import com.pivot.banking.account.domain.CustomerType;
import com.pivot.banking.account.domain.exception.BusinessRuleViolationException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/** Applies business-customer account requirements. */
@Component
public class BusinessAccountCreationPolicy implements AccountCreationPolicy {

    /** Allows checking accounts with one or more business holders only. */
    @Override
    public Mono<Void> validate(Account account, List<CustomerSnapshot> holders, List<Account> existingAccounts) {
        boolean allBusiness = !holders.isEmpty()
                && holders.stream().allMatch(holder ->
                holder.customerType() == CustomerType.BUSINESS && holder.active());
        if (!allBusiness || account.accountType() != AccountType.CHECKING) {
            return Mono.error(new BusinessRuleViolationException(
                    "Business customers may open checking accounts with one or more business holders"));
        }
        return Mono.empty();
    }
}
