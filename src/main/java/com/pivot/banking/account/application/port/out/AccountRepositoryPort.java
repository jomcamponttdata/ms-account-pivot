package com.pivot.banking.account.application.port.out;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountFilter;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.UUID;

/** Outbound persistence port for account aggregates. */
public interface AccountRepositoryPort {

  /** Stores an account and returns its persisted version. */
  Single<Account> save(Account account);

  /** Stores both accounts of a transfer in one transaction, in source-then-destination order. */
  Single<List<Account>> transfer(Account source, Account destination);

  /** Restores both accounts of a transfer in one transaction, in source-then-destination order. */
  Single<List<Account>> restore(Account source, Account destination);

  /** Retrieves an account by identifier. */
  Maybe<Account> findById(UUID id);

  /** Retrieves an ordered page of accounts matching the supplied filters. */
  Flowable<Account> findAll(AccountFilter filter, int page, int size);

  /** Deletes an existing account aggregate. */
  Completable delete(Account account);
}
