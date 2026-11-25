package com.pivot.banking.account.infrastructure.adapter.out.persistence;

import com.pivot.banking.account.application.port.out.AccountRepositoryPort;
import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountFilter;
import com.pivot.banking.account.domain.AuthorizedSigner;
import com.pivot.banking.account.domain.exception.BusinessRuleViolationException;
import com.pivot.banking.account.domain.exception.ConcurrentModificationException;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import reactor.adapter.rxjava.RxJava3Adapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Implements account persistence through reactive Spring Data R2DBC repositories. */
@Repository
public class R2dbcAccountRepositoryAdapter implements AccountRepositoryPort {

  private final SpringDataAccountRepository accounts;
  private final SpringDataAccountHolderRepository holders;
  private final SpringDataAuthorizedSignerRepository signers;
  private final R2dbcEntityTemplate template;

  /** Creates the adapter with the account repositories and entity template. */
  public R2dbcAccountRepositoryAdapter(
      SpringDataAccountRepository accounts,
      SpringDataAccountHolderRepository holders,
      SpringDataAuthorizedSignerRepository signers,
      R2dbcEntityTemplate template) {
    this.accounts = accounts;
    this.holders = holders;
    this.signers = signers;
    this.template = template;
  }

  /** Saves the account and its relations atomically. */
  @Override
  @Transactional
  public Single<Account> save(Account account) {
    return RxJava3Adapter.monoToSingle(saveReactor(account));
  }

  /** Persists the aggregate and relations using Spring Data's native Reactor publishers. */
  private Mono<Account> saveReactor(Account account) {
    return accounts
        .save(AccountEntity.fromDomain(account))
        .flatMap(
            entity ->
                holders
                    .deleteByAccountId(entity.getId())
                    .then(signers.deleteByAccountId(entity.getId()))
                    .thenMany(
                        Flux.fromIterable(
                                account.holders().stream()
                                    .map(
                                        customerId ->
                                            new AccountHolderEntity(
                                                UUID.randomUUID(),
                                                entity.getId(),
                                                customerId,
                                                account.holderType().name(),
                                                account.accountType().name()))
                                    .toList())
                            .concatMap(template::insert))
                    .thenMany(
                        Flux.fromIterable(
                                account.authorizedSigners().stream()
                                    .map(
                                        signer ->
                                            AuthorizedSignerEntity.fromDomain(
                                                entity.getId(), signer))
                                    .toList())
                            .concatMap(template::insert))
                    .then(load(entity)))
        .onErrorMap(
            OptimisticLockingFailureException.class,
            failure -> new ConcurrentModificationException(account.id()))
        .onErrorMap(
            DataIntegrityViolationException.class,
            failure ->
                isPersonalProductDuplicate(failure)
                    ? new BusinessRuleViolationException(
                        "A personal customer may have at most one account of this type")
                    : failure);
  }

  /**
   * Persists both transfer accounts atomically and returns them in source-then-destination order.
   */
  @Override
  @Transactional
  public Single<List<Account>> transfer(Account source, Account destination) {
    return RxJava3Adapter.monoToSingle(saveBothReactor(source, destination));
  }

  /**
   * Restores both transfer accounts atomically and returns them in source-then-destination order.
   */
  @Override
  @Transactional
  public Single<List<Account>> restore(Account source, Account destination) {
    return RxJava3Adapter.monoToSingle(saveBothReactor(source, destination));
  }

  /** Saves both aggregates sequentially with Reactor so one transaction covers both writes. */
  private Mono<List<Account>> saveBothReactor(Account source, Account destination) {
    return Flux.concat(saveReactor(source), saveReactor(destination)).collectList();
  }

  /** Finds an account and hydrates its holder and signer relations. */
  @Override
  public Maybe<Account> findById(UUID id) {
    return RxJava3Adapter.monoToMaybe(accounts.findById(id).flatMap(this::load));
  }

  /** Finds a stable ordered page matching account-type and holder filters. */
  @Override
  public Flowable<Account> findAll(AccountFilter filter, int page, int size) {
    Mono<List<UUID>> accountIds =
        filter.customerId() == null
            ? Mono.just(List.of())
            : holders
                .findByCustomerId(filter.customerId())
                .map(AccountHolderEntity::getAccountId)
                .collectList();
    Flux<Account> result =
        accountIds.flatMapMany(
            ids -> {
              if (filter.customerId() != null && ids.isEmpty()) {
                return Flux.empty();
              }
              Criteria criteria = Criteria.empty();
              if (filter.accountType() != null) {
                criteria = criteria.and("accountType").is(filter.accountType().name());
              }
              if (filter.customerId() != null) {
                criteria = criteria.and("id").in(ids);
              }
              Query query =
                  Query.query(criteria)
                      .sort(Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")))
                      .limit(size)
                      .offset((long) page * size);
              return template.select(query, AccountEntity.class).concatMap(this::load);
            });
    return RxJava3Adapter.fluxToFlowable(result);
  }

  /** Deletes holder and signer relations before deleting the account row. */
  @Override
  @Transactional
  public Completable delete(Account account) {
    return RxJava3Adapter.monoToCompletable(deleteReactor(account));
  }

  /** Deletes all rows for an aggregate using Spring Data's native Reactor publishers. */
  private Mono<Void> deleteReactor(Account account) {
    return holders
        .deleteByAccountId(account.id())
        .then(signers.deleteByAccountId(account.id()))
        .then(accounts.delete(AccountEntity.fromDomain(account)))
        .onErrorMap(
            OptimisticLockingFailureException.class,
            failure -> new ConcurrentModificationException(account.id()));
  }

  /** Rehydrates an account scalar row with its holder and signer relations. */
  private Mono<Account> load(AccountEntity entity) {
    Mono<List<AccountHolderEntity>> accountHolders =
        holders.findByAccountId(entity.getId()).collectList();
    Mono<List<AuthorizedSigner>> accountSigners =
        signers.findByAccountId(entity.getId()).map(AuthorizedSignerEntity::toDomain).collectList();
    return Mono.zip(accountHolders, accountSigners)
        .map(
            relations -> {
              List<UUID> holderIds =
                  relations.getT1().stream().map(AccountHolderEntity::getCustomerId).toList();
              var holderType =
                  relations.getT1().stream()
                      .findFirst()
                      .map(
                          relation ->
                              com.pivot.banking.account.domain.CustomerType.valueOf(
                                  relation.getCustomerType()))
                      .orElseThrow();
              return entity.toDomain(holderIds, holderType, relations.getT2());
            });
  }

  /** Identifies the database unique-index violation for personal savings/checking products. */
  private boolean isPersonalProductDuplicate(Throwable failure) {
    Throwable cause = failure;
    while (cause != null) {
      if (cause.getMessage() != null
          && cause.getMessage().contains("uq_personal_account_product")) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }
}
