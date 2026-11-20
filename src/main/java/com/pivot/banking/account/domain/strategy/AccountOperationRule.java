package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.Money;
import com.pivot.banking.account.domain.MovementType;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

/** Validates a monetary operation for one account type. */
public interface AccountOperationRule {

    /** Validates monthly product limits, operation date, currency, and funds. */
    Mono<Void> validate(Account account, MovementType movementType, Money amount, LocalDate date);
}
