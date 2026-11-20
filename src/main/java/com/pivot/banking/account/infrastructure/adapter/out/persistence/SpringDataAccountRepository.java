package com.pivot.banking.account.infrastructure.adapter.out.persistence;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import java.util.UUID;

/** Spring Data repository for account rows. */
public interface SpringDataAccountRepository extends ReactiveCrudRepository<AccountEntity, UUID> {
}
