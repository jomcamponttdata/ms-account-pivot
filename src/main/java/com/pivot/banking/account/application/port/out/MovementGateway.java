package com.pivot.banking.account.application.port.out;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.Money;
import com.pivot.banking.account.domain.MovementReceipt;
import com.pivot.banking.account.domain.MovementType;
import reactor.core.publisher.Mono;

/** Outbound client port for registering a movement in transaction-service. */
public interface MovementGateway {

    /** Registers the movement and reports definitive versus ambiguous downstream failures. */
    Mono<MovementReceipt> register(Account account, MovementType movementType, Money amount, String description);
}
