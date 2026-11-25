package com.pivot.banking.account.infrastructure.adapter.out.persistence;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Spring Data repository for account-holder relations. */
public interface SpringDataAccountHolderRepository
    extends ReactiveCrudRepository<AccountHolderEntity, UUID> {

  /** Finds holder relations belonging to one account. */
  Flux<AccountHolderEntity> findByAccountId(UUID accountId);

  /** Finds holder relations for one customer. */
  Flux<AccountHolderEntity> findByCustomerId(UUID customerId);

  /** Deletes all holder relations for one account. */
  Mono<Void> deleteByAccountId(UUID accountId);
}
