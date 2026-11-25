package com.pivot.banking.account.infrastructure.adapter.out.persistence;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

/** Spring Data repository for account rows. */
public interface SpringDataAccountRepository extends ReactiveCrudRepository<AccountEntity, UUID> {}
