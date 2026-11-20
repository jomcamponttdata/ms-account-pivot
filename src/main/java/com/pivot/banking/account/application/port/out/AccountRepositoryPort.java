package com.pivot.banking.account.application.port.out;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountFilter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Outbound persistence port for account aggregates. */
public interface AccountRepositoryPort {

    /** Stores an account and returns its persisted version. */
    Mono<Account> save(Account account);

    /** Retrieves an account by identifier. */
    Mono<Account> findById(UUID id);

    /** Retrieves an ordered page of accounts matching the supplied filters. */
    Flux<Account> findAll(AccountFilter filter, int page, int size);

    /** Deletes an existing account aggregate. */
    Mono<Void> delete(Account account);
}
