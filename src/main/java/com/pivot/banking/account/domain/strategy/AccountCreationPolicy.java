package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.CustomerSnapshot;
import reactor.core.publisher.Mono;

import java.util.List;

/** Validates account creation against customer-type-specific business rules. */
public interface AccountCreationPolicy {

    /** Validates holders, product type, and accounts already owned by those holders. */
    Mono<Void> validate(Account account, List<CustomerSnapshot> holders, List<Account> existingAccounts);
}
