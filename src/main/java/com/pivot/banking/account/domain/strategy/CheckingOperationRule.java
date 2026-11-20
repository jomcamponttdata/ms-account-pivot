package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.Money;
import com.pivot.banking.account.domain.MovementType;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

/** Applies currency and sufficient-funds checks without a monthly movement cap. */
@Component
public class CheckingOperationRule implements AccountOperationRule {

    /** Validates currency and ensures withdrawals do not overdraw the account. */
    @Override
    public Mono<Void> validate(Account account, MovementType movementType, Money amount, LocalDate date) {
        return SavingsOperationRule.validateCurrencyAndFunds(account, movementType, amount);
    }
}
