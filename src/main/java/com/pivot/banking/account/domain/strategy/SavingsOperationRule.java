package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.Money;
import com.pivot.banking.account.domain.MovementType;
import com.pivot.banking.account.domain.exception.BusinessRuleViolationException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;

/** Enforces the savings account monthly movement limit. */
@Component
public class SavingsOperationRule implements AccountOperationRule {

    /** Rejects operations once the current month's movement allowance is exhausted. */
    @Override
    public void validate(Account account, MovementType movementType, Money amount, LocalDate date) {
        int count = YearMonth.from(date).equals(account.movementsPeriod())
                ? account.movementsInCurrentMonth() : 0;
        if (count >= account.maxMonthlyMovements()) {
            throw new BusinessRuleViolationException("Monthly movement limit reached");
        }
        validateCurrencyAndFunds(account, movementType, amount);
    }

    /** Enforces currency consistency and prevents withdrawals beyond the available balance. */
    static void validateCurrencyAndFunds(Account account, MovementType movementType, Money amount) {
        if (!account.balance().currency().equals(amount.currency())) {
            throw new BusinessRuleViolationException("Operation currency must match account currency");
        }
        if (movementType == MovementType.WITHDRAWAL && !account.balance().isGreaterThanOrEqualTo(amount)) {
            throw new BusinessRuleViolationException("Insufficient account balance");
        }
    }
}
