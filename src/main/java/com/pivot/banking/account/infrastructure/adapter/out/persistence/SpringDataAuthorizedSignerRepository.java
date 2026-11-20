package com.pivot.banking.account.infrastructure.adapter.out.persistence;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Spring Data repository for account authorized signers. */
public interface SpringDataAuthorizedSignerRepository
        extends ReactiveCrudRepository<AuthorizedSignerEntity, UUID> {

    /** Finds signers belonging to one account. */
    Flux<AuthorizedSignerEntity> findByAccountId(UUID accountId);

    /** Deletes all signers belonging to one account. */
    Mono<Void> deleteByAccountId(UUID accountId);
}
