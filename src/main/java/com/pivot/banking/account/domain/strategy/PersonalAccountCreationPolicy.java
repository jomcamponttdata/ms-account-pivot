package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.CustomerSnapshot;
import com.pivot.banking.account.domain.CustomerType;
import com.pivot.banking.account.domain.exception.BusinessRuleViolationException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/** Applies personal-customer account limits and ownership requirements. */
@Component
public class PersonalAccountCreationPolicy implements AccountCreationPolicy {

    /** Enforces one personal holder and at most one savings/checking account of each type. */
    @Override
    public Mono<Void> validate(Account account, List<CustomerSnapshot> holders, List<Account> existingAccounts) {
        boolean validHolder = holders.size() == 1
                && holders.get(0).customerType() == CustomerType.PERSONAL
                && holders.get(0).active();
        if (!validHolder) {
            return Mono.error(new BusinessRuleViolationException(
                    "Personal accounts require exactly one personal customer as holder"));
        }
        boolean duplicateProduct = account.accountType() != AccountType.FIXED_TERM
                && existingAccounts.stream().anyMatch(existing ->
                existing.accountType() == account.accountType());
        if (duplicateProduct) {
            return Mono.error(new BusinessRuleViolationException(
                    "A personal customer may have at most one account of this type"));
        }
        return Mono.empty();
    }
}
